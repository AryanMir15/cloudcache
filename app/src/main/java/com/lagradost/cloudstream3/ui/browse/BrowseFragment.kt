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
import com.lagradost.cloudstream3.syncproviders.providers.TmdbApi
import com.lagradost.cloudstream3.ui.AniListFilterUtils
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.TvType
import androidx.preference.PreferenceManager
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.ArrayAdapter

class BrowseFragment : BaseFragment<FragmentBrowseBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentBrowseBinding::inflate)
) {

    private val viewModel: BrowseViewModel by activityViewModels()

    // Provider selection - will be restored from SharedPreferences in onResume
    private var selectedProvider = FilterProvider.ANILIST
    
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
    
    // TMDB filter state - synced with ViewModel
    private var selectedTmdbFormat = TmdbFormat.MOVIE
    private var selectedTmdbGenres = mutableSetOf<String>()
    private var excludedTmdbGenres = mutableSetOf<String>()
    private var selectedTmdbYear = "All"
    private var selectedTmdbCountry = "All"
    private var selectedTmdbProvider = "All"
    private var selectedTmdbTrending = "Off"
    private var selectedTmdbIncludeAdult = false
    private var selectedTmdbKeywords = ""
    private var selectedTmdbMinVotes = 0

    // Track chip visibility to only update margin when it actually changes
    private var wereChipsVisible = false
    
    // Track last known top bar height to prevent redundant padding updates
    private var lastKnownTopBarHeight = -1
    
    // Track ongoing padding animation to prevent conflicts
    private var isAnimatingPadding = false

    private var resultsList = emptyList<BrowseMediaItem>()
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
    
    // Provider options for selector
    private val providerOptions = listOf("AniList", "TMDB")

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

        // TAB_STATE_FIX: Restore provider state before setting up UI
        restoreProviderState()
        
        // Load saved default filters
        loadDefaultFilters()

        setupUI()
        // Auto-load results with default filters only if ViewModel has no data
        if (viewModel.uiState.value?.results?.isEmpty() == true) {
            android.util.Log.d("BrowseFragment", "onBindingCreated: ViewModel has no data, calling loadResults()")
            loadResults()
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
            
            selectedProvider = filters?.provider ?: selectedProvider
            selectedGenres = filters?.genres?.toMutableSet() ?: mutableSetOf()
            selectedTags = filters?.tags?.toMutableSet() ?: mutableSetOf()
            excludedGenres = filters?.excludedGenres?.toMutableSet() ?: mutableSetOf()
            excludedTags = filters?.excludedTags?.toMutableSet() ?: mutableSetOf()
            selectedYear = filters?.year ?: "All"
            selectedSeason = filters?.season ?: "All"
            selectedFormat = filters?.format ?: "All"
            selectedSort = filters?.sort ?: "Popularity"
            
            // Restore TMDB-specific filters
            selectedTmdbFormat = filters?.tmdbFormat ?: TmdbFormat.MOVIE
            selectedTmdbGenres = filters?.tmdbGenres?.toMutableSet() ?: mutableSetOf()
            excludedTmdbGenres = filters?.tmdbExcludedGenres?.toMutableSet() ?: mutableSetOf()
            selectedTmdbYear = filters?.tmdbYear ?: "All"
            selectedTmdbCountry = filters?.tmdbCountry ?: "All"
            selectedTmdbProvider = filters?.tmdbProvider ?: "All"
            selectedTmdbTrending = filters?.tmdbTrending ?: "Off"
            selectedTmdbIncludeAdult = filters?.tmdbIncludeAdult ?: false
            selectedTmdbKeywords = filters?.tmdbKeywords ?: ""
            selectedTmdbMinVotes = filters?.tmdbMinVotes ?: 0
            selectedSort = syncSortValueForProvider(selectedSort, selectedProvider)
            
            
            android.util.Log.d("STATE_SYNC_FIX", "onBindingCreated: Restored local selectedGenres = $selectedGenres")
            android.util.Log.d("STATE_SYNC_FIX", "onBindingCreated: Restored local selectedTags = $selectedTags")
            android.util.Log.d("STATE_SYNC_FIX", "onBindingCreated: Restored local excludedGenres = $excludedGenres")
            android.util.Log.d("STATE_SYNC_FIX", "onBindingCreated: Restored local excludedTags = $excludedTags")
            android.util.Log.d("STATE_SYNC_FIX", "onBindingCreated: Restored TMDB filters: format=$selectedTmdbFormat, genres=$selectedTmdbGenres, country=$selectedTmdbCountry, trending=$selectedTmdbTrending")
            android.util.Log.d("STATE_SYNC_FIX", "========== Filter state restoration completed ==========")

            // Set initial search hint based on current provider
            val initialSearchHint = when (selectedProvider) {
                FilterProvider.ANILIST -> "Search in AniList"
                FilterProvider.TMDB -> "Search in TMDB"
            }
            binding?.browseSearch?.queryHint = initialSearchHint
            android.util.Log.d("STATE_SYNC_FIX", "onBindingCreated: Set initial search hint to: $initialSearchHint")

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
        
        // TAB_STATE_FIX: Restore provider state from SharedPreferences when returning to Browse tab
        restoreProviderState()
        
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
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: view != null = ${view != null}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: activity != null = ${activity != null}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: context != null = ${context != null}")
        
        // TAB_STATE_FIX: Save provider state when leaving Browse tab
        saveProviderState()
        
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
                        // SCROLL_POSITION_FIX: Don't scroll to top after animation, preserve scroll position
                        // browseResults.scrollToPosition(0)
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
                    
                    // CLEAR_SEARCH_ENTER_FIX: Handle empty query to show full data list
                    if (query.isNullOrBlank()) {
                        android.util.Log.d("CLEAR_SEARCH_ENTER_FIX", "Empty query submitted, showing full data list")
                        searchQuery = null
                        currentAniListPage = 1
                        
                        // Clear the search view
                        browseSearch.setQuery("", false)
                        browseSearch.clearFocus()
                        
                        // Re-enable filters (discover mode)
                        updateFilterStatesForSearch(isSearchMode = false)
                        
                        // Load full data list
                        loadResults()
                        return true
                    }
                    
                    searchQuery = query
                    currentAniListPage = 1
                    
                    // Update filter states based on search mode
                    updateFilterStatesForSearch(isSearchMode = true)
                    
                    android.util.Log.d("BrowseFragment", "onQueryTextSubmit: currentAniListPage reset to 1, calling loadResults()")
                    loadResults()
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    android.util.Log.d("BrowseFragment", "onQueryTextChange: newText=$newText")
                    
                    // CLEAR_SEARCH_ENTER_FIX: Handle empty text change to show full data list
                    if (newText.isNullOrBlank() && searchQuery != null) {
                        android.util.Log.d("CLEAR_SEARCH_ENTER_FIX", "Search text cleared, showing full data list")
                        searchQuery = null
                        currentAniListPage = 1
                        
                        // Re-enable filters (discover mode)
                        updateFilterStatesForSearch(isSearchMode = false)
                        
                        // Load full data list
                        loadResults()
                    }
                    
                    // Update filter states based on whether user is typing or not
                    val isSearchMode = !newText.isNullOrBlank()
                    updateFilterStatesForSearch(isSearchMode = isSearchMode)
                    
                    return true
                }
            })
            
            // Setup search close button to clear search and return to discover mode
            browseSearch.setOnCloseListener {
                android.util.Log.d("BrowseFragment", "Search close button clicked - clearing search")
                searchQuery = null
                currentAniListPage = 1
                
                // Clear the search view
                browseSearch.setQuery("", false)
                browseSearch.clearFocus()
                
                // Re-enable filters (discover mode)
                updateFilterStatesForSearch(isSearchMode = false)
                
                // Reload results in discover mode
                loadResults()
                true
            }

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

            // Setup swap metadata button click listener
            swapMetadataButton.setOnClickListener {
                android.util.Log.d("UI_DEBUG_LOG", "========== SWAP_METADATA_BUTTON CLICKED ==========")
                android.util.Log.d("PROVIDER_SWITCH", "Swap metadata button clicked")
                android.util.Log.d("UI_DEBUG_LOG", "Current provider before switch: $selectedProvider")
                
                // Toggle between AniList and TMDB
                selectedProvider = when (selectedProvider) {
                    FilterProvider.ANILIST -> {
                        android.util.Log.d("PROVIDER_SWITCH", "Switching from AniList to TMDB")
                        FilterProvider.TMDB
                    }
                    FilterProvider.TMDB -> {
                        android.util.Log.d("PROVIDER_SWITCH", "Switching from TMDB to AniList")
                        FilterProvider.ANILIST
                    }
                }
                
                // Save current sort for the provider we're leaving, then load the target provider's saved sort
                val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                // We already switched selectedProvider above, so the OLD provider is the opposite
                val oldSortKey = when (selectedProvider) {
                    FilterProvider.TMDB -> "browse_sort_anilist"    // just switched to TMDB, old was AniList
                    FilterProvider.ANILIST -> "browse_sort_tmdb"    // just switched to AniList, old was TMDB
                }
                prefs.edit().putString(oldSortKey, selectedSort).apply()

                // Load the target provider's previously saved sort, or fall back to its default
                val targetSortKey = when (selectedProvider) {
                    FilterProvider.ANILIST -> "browse_sort_anilist"
                    FilterProvider.TMDB -> "browse_sort_tmdb"
                }
                val targetDefaultSort = when (selectedProvider) {
                    FilterProvider.ANILIST -> "Popularity"
                    FilterProvider.TMDB -> "Popularity (High to Low)"
                }
                selectedSort = prefs.getString(targetSortKey, null) ?: targetDefaultSort
                android.util.Log.d("PROVIDER_SWITCH", "Restored sort for $selectedProvider: $selectedSort")
                
                android.util.Log.d("UI_DEBUG_LOG", "New provider after switch: $selectedProvider")
                
                // Update search hint based on provider
                val newSearchHint = when (selectedProvider) {
                    FilterProvider.ANILIST -> "Search in AniList"
                    FilterProvider.TMDB -> "Search in TMDB"
                }
                binding?.browseSearch?.queryHint = newSearchHint
                android.util.Log.d("UI_DEBUG_LOG", "Updated search hint to: $newSearchHint")
                
                // METADATA_LANGUAGE_FIX: Update metadata language button state based on provider
                val isAniListSelected = selectedProvider == FilterProvider.ANILIST
                binding?.apply {
                    metadataLanguageDropdown.alpha = if (isAniListSelected) 0.5f else 1.0f
                    metadataLanguageDropdown.isEnabled = !isAniListSelected
                    metadataLanguageText.alpha = if (isAniListSelected) 0.5f else 1.0f
                    metadataLanguageText.isEnabled = !isAniListSelected
                }
                android.util.Log.d("METADATA_LANGUAGE_FIX", "Updated metadata language button state - isAniListSelected: $isAniListSelected")
                
                // Reset page and reload results with new provider
                currentAniListPage = 1
                viewModel.resetPage()
                loadResults()
                
                // TMDB_SEARCH_BUTTON_FIX: Update filter states when switching providers
                val actualSearchMode = !searchQuery.isNullOrBlank()
                updateFilterStatesForSearch(actualSearchMode)
                android.util.Log.d("TMDB_SEARCH_BUTTON_FIX", "Updated filter states after provider switch - searchQuery='$searchQuery', actualSearchMode=$actualSearchMode")
                
                // Update UI to reflect provider change
                updateUI()
                android.util.Log.d("UI_DEBUG_LOG", "========== SWAP_METADATA_BUTTON COMPLETED ==========")
            }

            // METADATA_LANGUAGE_FIX: Set initial metadata language button state based on provider
            val isAniListSelected = selectedProvider == FilterProvider.ANILIST
            binding?.apply {
                metadataLanguageDropdown.alpha = if (isAniListSelected) 0.5f else 1.0f
                metadataLanguageDropdown.isEnabled = !isAniListSelected
                metadataLanguageText.alpha = if (isAniListSelected) 0.5f else 1.0f
                metadataLanguageText.isEnabled = !isAniListSelected
            }
            android.util.Log.d("METADATA_LANGUAGE_FIX", "Initial metadata language button state - isAniListSelected: $isAniListSelected")

            // Setup metadata language dropdown click listener
            binding?.metadataLanguageDropdown?.setOnClickListener {
                android.util.Log.d("UI_DEBUG_LOG", "METADATA_LANGUAGE_DROPDOWN_CLICKED")
                showMetadataLanguageDialog()
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
        android.util.Log.d("TOPBAR_DEBUG", "========== updateUI called ==========")
        android.util.Log.d("TOPBAR_DEBUG", "selectedProvider=$selectedProvider")
        android.util.Log.d("TOPBAR_DEBUG", "TMDB state: format=$selectedTmdbFormat, year=$selectedTmdbYear, country=$selectedTmdbCountry, provider=$selectedTmdbProvider")
        android.util.Log.d("TOPBAR_DEBUG", "AniList state: format=$selectedFormat, year=$selectedYear, season=$selectedSeason")
        android.util.Log.d("TOPBAR_DEBUG", "searchQuery=$searchQuery")
        
        binding?.apply {
            // Update filter labels based on provider
            val yearText = "Year: ${if (selectedProvider == FilterProvider.TMDB) selectedTmdbYear else selectedYear}"
            yearLabel.text = yearText
            android.util.Log.d("TOPBAR_DEBUG", "yearLabel set to: $yearText")
            
            // Secondary filter: Season for AniList, Country for TMDB
            val secondaryText = when (selectedProvider) {
                FilterProvider.ANILIST -> "Season: $selectedSeason"
                FilterProvider.TMDB -> "Country: $selectedTmdbCountry"
            }
            secondaryFilterLabel.text = secondaryText
            android.util.Log.d("TOPBAR_DEBUG", "secondaryFilterLabel set to: $secondaryText")
            
            // Format shows AniList format or TMDB Movie/TV
            val formatText = when (selectedProvider) {
                FilterProvider.ANILIST -> "Format: $selectedFormat"
                FilterProvider.TMDB -> "Format: ${if (selectedTmdbFormat == TmdbFormat.MOVIE) "Movie" else "TV"}"
            }
            formatLabel.text = formatText
            android.util.Log.d("TOPBAR_DEBUG", "formatLabel set to: $formatText")
            
            sortLabel.text = "Sort: $selectedSort"
            android.util.Log.d("UI_DEBUG_LOG", "sortLabel set to: Sort: $selectedSort")

            // Update streaming provider chip (TMDB only)
            android.util.Log.d("UI_DEBUG_LOG", "========== STREAMING_PROVIDER_CHIP_UPDATE START ==========")
            android.util.Log.d("UI_DEBUG_LOG", "STREAMING_PROVIDER_CHIP_DEBUG: selectedProvider=$selectedProvider, selectedTmdbProvider=$selectedTmdbProvider")
            android.util.Log.d("UI_DEBUG_LOG", "STREAMING_PROVIDER_CHIP_DEBUG: TMDB provider check - isTMDB=${selectedProvider == FilterProvider.TMDB}, isNotAll=${selectedTmdbProvider != "All"}")
            
            if (selectedProvider == FilterProvider.TMDB && selectedTmdbProvider != "All") {
                android.util.Log.d("UI_DEBUG_LOG", "STREAMING_PROVIDER_CHIP_DEBUG: SHOWING chip with provider: $selectedTmdbProvider")
                binding?.streamingProviderChip?.visibility = View.VISIBLE
                binding?.streamingProviderChip?.text = selectedTmdbProvider
                
                // Set brand color for streaming provider
                val brandColor = when (selectedTmdbProvider) {
                    "Netflix" -> "#E50914"  // Netflix Red (hero color)
                    "Disney+", "Disney Plus" -> "#02E7C0"  // Disney+ Aurora/Teal (2024 rebrand)
                    "Max", "HBO Max" -> "#002CFF"     // Max Bright Blue (HBO Max rebrand)
                    "Amazon Prime", "Amazon Prime Video" -> "#00A8E1"  // Prime Blue (official)
                    "Amazon Video" -> "#00A8E1"  // Amazon Video Blue
                    "Hulu" -> "#1CE783"    // Hulu Green (official neon green)
                    "Apple TV+", "Apple TV Plus" -> "#000000"  // Apple TV+ Black (minimalist)
                    "Paramount+" -> "#0064FF"  // Paramount+ Vibrant Blue (2021 update)
                    "Peacock" -> "#FFC224"    // Peacock Yellow (primary feather color)
                    "Crunchyroll" -> "#F47521"  // Crunchyroll Orange
                    "YouTube" -> "#FF0000"    // YouTube Red
                    "Google Play Movies" -> "#4285F4"  // Google Blue
                    "iTunes" -> "#A2AAAD"    // iTunes Gray
                    "Funimation" -> "#410099"  // Funimation Purple
                    "Tubi TV" -> "#F96302"   // Tubi Orange
                    "Pluto TV" -> "#F8E71C"   // Pluto TV Yellow
                    "Rakuten Viki" -> "#00AEEF"  // Viki Blue
                    "Microsoft Store" -> "#0067B8"  // Microsoft Blue
                    "Starz" -> "#000000"     // Starz Black
                    "Showtime" -> "#E6192E"  // Showtime Red
                    "AMC+" -> "#C1121C"      // AMC+ Red
                    "Shudder" -> "#E10613"    // Shudder Red
                    "MUBI" -> "#00ADEF"      // MUBI Blue
                    "Criterion Channel" -> "#000000"  // Criterion Channel Black
                    else -> "#4D4D4D"       // Default Gray
                }
                android.util.Log.d("UI_DEBUG_LOG", "STREAMING_PROVIDER_CHIP_DEBUG: Determined brand color: $brandColor")
                android.util.Log.d("UI_DEBUG_LOG", "STREAMING_PROVIDER_CHIP_DEBUG: Applying brand color to chip")
                
                try {
                    val colorStateList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor(brandColor)
                    )
                    binding?.streamingProviderChip?.chipBackgroundColor = colorStateList
                    
                    // Also set chip stroke and text colors for better visibility
                    binding?.streamingProviderChip?.chipStrokeColor = colorStateList
                    binding?.streamingProviderChip?.setTextColor(android.graphics.Color.WHITE)
                    
                    // Force chip to use custom background
                    binding?.streamingProviderChip?.chipCornerRadius = 16f
                    
                    android.util.Log.d("UI_DEBUG_LOG", "STREAMING_PROVIDER_CHIP_DEBUG: Brand color applied successfully: $brandColor")
                    android.util.Log.d("UI_DEBUG_LOG", "STREAMING_PROVIDER_CHIP_DEBUG: Final chip background color: ${binding?.streamingProviderChip?.chipBackgroundColor}")
                    android.util.Log.d("UI_DEBUG_LOG", "STREAMING_PROVIDER_CHIP_DEBUG: Final chip stroke color: ${binding?.streamingProviderChip?.chipStrokeColor}")
                    android.util.Log.d("UI_DEBUG_LOG", "STREAMING_PROVIDER_CHIP_DEBUG: Final chip text color: ${binding?.streamingProviderChip?.currentTextColor}")
                } catch (e: Exception) {
                    android.util.Log.e("UI_DEBUG_LOG", "STREAMING_PROVIDER_CHIP_DEBUG: ERROR applying brand color", e)
                }
            } else {
                android.util.Log.d("UI_DEBUG_LOG", "STREAMING_PROVIDER_CHIP_DEBUG: HIDING chip - provider=$selectedProvider, tmdbProvider=$selectedTmdbProvider")
                binding?.streamingProviderChip?.visibility = View.GONE
            }
            android.util.Log.d("UI_DEBUG_LOG", "========== STREAMING_PROVIDER_CHIP_UPDATE COMPLETED ==========")

            // DUPLICATE_ITEMS_FIX: Deduplicate results before submitting to adapter
            val searchResponses = resultsList.toSearchResponses()
            val deduplicatedResponses = searchResponses.distinctBy { it.url + it.name }
            val duplicateCount = searchResponses.size - deduplicatedResponses.size
            
            if (duplicateCount > 0) {
                android.util.Log.d("DUPLICATE_ITEMS_FIX", "Removed $duplicateCount duplicate items")
            }
            
            (browseResults.adapter as? SearchAdapter)?.submitList(deduplicatedResponses)
            android.util.Log.d("BrowseFragment", "updateUI: Submitted ${deduplicatedResponses.size} deduplicated results to adapter (original: ${searchResponses.size})")

            // Show/hide no results text and end of results toast
            if (resultsList.isEmpty() && viewModel.uiState.value?.isLoading != true) {
                // No results at all and not loading
                noResultsText.visibility = View.VISIBLE
                endOfResultsToast.visibility = View.GONE
                endOfResultsToastTop?.visibility = View.GONE
            } else if (!hasMoreResults && viewModel.uiState.value?.isLoading != true && resultsList.isNotEmpty()) {
                // We have results but no more pages to load
                noResultsText.visibility = View.GONE
                
                // Show top toast if keyboard is visible, otherwise show bottom toast
                val toastView = if (isKeyboardVisible()) {
                    android.util.Log.d("BrowseFragment", "[TOAST_POSITION] Using TOP toast (keyboard visible)")
                    endOfResultsToastTop
                } else {
                    android.util.Log.d("BrowseFragment", "[TOAST_POSITION] Using BOTTOM toast (keyboard not visible)")
                    endOfResultsToast
                }
                
                // Hide the other toast
                if (isKeyboardVisible()) {
                    endOfResultsToast?.visibility = View.GONE
                } else {
                    endOfResultsToastTop?.visibility = View.GONE
                }
                
                toastView?.apply {
                    android.util.Log.d("BrowseFragment", "[TOAST_POSITION] Toast view id: ${id}, visibility before: $visibility")
                    visibility = View.VISIBLE
                    android.util.Log.d("BrowseFragment", "[TOAST_POSITION] Toast visibility set to VISIBLE")
                    alpha = 0f
                    animate()
                        .alpha(1f)
                        .setDuration(300)
                        .start()
                    android.util.Log.d("BrowseFragment", "[TOAST_POSITION] Toast animation started, alpha: $alpha")
                    // Auto-hide after 2 seconds
                    postDelayed({
                        animate()
                            .alpha(0f)
                            .setDuration(300)
                            .withEndAction {
                                visibility = View.GONE
                            }
                            .start()
                    }, 2000)
                }
            } else {
                // We have results and more to load, or currently loading
                noResultsText.visibility = View.GONE
                endOfResultsToast.visibility = View.GONE
                endOfResultsToastTop?.visibility = View.GONE
            }

            // Show/hide loading bar in search bar
            browseSearchLoadingBar.alpha = if (viewModel.uiState.value?.isLoading == true) 1f else 0f
        }

        // Update genre and tag chips
        updateGenreChips()
        updateTagsChips()
        
        // Update filter states based on search mode (only for TMDB)
        if (selectedProvider == FilterProvider.TMDB) {
            val isSearchMode = !searchQuery.isNullOrBlank()
            android.util.Log.d("SEARCH_FILTER_DEBUG", "updateUI: searchQuery='$searchQuery', isSearchMode=$isSearchMode")
            updateFilterStatesForSearch(isSearchMode = isSearchMode)
        }
        
        android.util.Log.d("BrowseFragment", "========== updateUI completed ==========")
    }

    private fun isKeyboardVisible(): Boolean {
        val rootView = binding?.root ?: return false
        val rect = android.graphics.Rect()
        rootView.getWindowVisibleDisplayFrame(rect)
        val screenHeight = rootView.height
        val keypadHeight = screenHeight - rect.bottom
        val threshold = screenHeight * 0.15
        val isVisible = keypadHeight > threshold
        android.util.Log.d("BrowseFragment", "[KEYBOARD_CHECK] screenHeight: $screenHeight, keypadHeight: $keypadHeight, threshold: $threshold, isVisible: $isVisible")
        return isVisible // Keyboard is visible if it takes more than 15% of screen
    }

    private fun updateGenreChips() {
        android.util.Log.d("GENRE_CHIPS_DEBUG", "========== updateGenreChips called ==========")
        android.util.Log.d("GENRE_CHIPS_DEBUG", "selectedProvider=$selectedProvider")
        android.util.Log.d("GENRE_CHIPS_DEBUG", "selectedTmdbGenres=$selectedTmdbGenres")
        android.util.Log.d("GENRE_CHIPS_DEBUG", "selectedGenres=$selectedGenres")
        android.util.Log.d("GENRE_CHIPS_DEBUG", "excludedTmdbGenres=$excludedTmdbGenres")
        android.util.Log.d("GENRE_CHIPS_DEBUG", "excludedGenres=$excludedGenres")
        
        binding?.genreChips?.removeAllViews()
        val chipGroup = binding?.genreChips ?: return

        // Get included and excluded genres based on current provider
        val includedGenres = when (selectedProvider) {
            FilterProvider.TMDB -> selectedTmdbGenres
            FilterProvider.ANILIST -> selectedGenres
        }
        
        val excludedGenres = when (selectedProvider) {
            FilterProvider.TMDB -> excludedTmdbGenres
            FilterProvider.ANILIST -> excludedGenres
        }
        
        val allGenres = includedGenres + excludedGenres
        android.util.Log.d("GENRE_CHIPS_DEBUG", "includedGenres=$includedGenres, excludedGenres=$excludedGenres, total=${allGenres.size}")
        android.util.Log.d("GENRE_CHIPS_DEBUG", "DEBUG: allGenres.isEmpty()=${allGenres.isEmpty()}, chipGroup.visibility=${binding?.genreChips?.visibility}")

        if (allGenres.isNotEmpty()) {
            android.util.Log.d("GENRE_CHIPS_DEBUG", "Showing genre chips, count=${allGenres.size}")
            chipGroup.visibility = View.VISIBLE
            
            // Add included genres first (normal styling)
            includedGenres.forEach { genre ->
                android.util.Log.d("GENRE_CHIPS_DEBUG", "Creating INCLUDED chip for genre: $genre")
                val chip = com.google.android.material.chip.Chip(requireContext(), null, R.style.ChipFilled).apply {
                    text = genre
                    isCloseIconVisible = true
                    chipBackgroundColor = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.primaryBlackBackground))
                    chipStrokeColor = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.primaryBlackBackground))
                    chipStrokeWidth = 1f
                    setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.white))
                    setOnCloseIconClickListener {
                        android.util.Log.d("GENRE_CHIPS_DEBUG", "Chip close clicked for INCLUDED genre: $genre")
                        when (selectedProvider) {
                            FilterProvider.TMDB -> selectedTmdbGenres.remove(genre)
                            FilterProvider.ANILIST -> selectedGenres.remove(genre)
                        }
                        updateGenreChips()
                        // Reset ViewModel page and reload results
                        viewModel.resetPage()
                        currentAniListPage = 1
                        loadResults()
                    }
                }
                chipGroup.addView(chip)
            }
            
            // Add excluded genres with grey styling and line-through
            excludedGenres.forEach { genre ->
                android.util.Log.d("GENRE_CHIPS_DEBUG", "Creating EXCLUDED chip for genre: $genre")
                val chip = com.google.android.material.chip.Chip(requireContext(), null, R.style.ChipFilled).apply {
                    text = genre
                    isCloseIconVisible = true
                    alpha = 0.5f // Low opacity for excluded chips
                    chipBackgroundColor = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
                    chipStrokeColor = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
                    chipStrokeWidth = 1f
                    setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.white))
                    paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG // Line through effect
                    setOnCloseIconClickListener {
                        android.util.Log.d("GENRE_CHIPS_DEBUG", "Chip close clicked for EXCLUDED genre: $genre")
                        when (selectedProvider) {
                            FilterProvider.TMDB -> excludedTmdbGenres.remove(genre)
                            FilterProvider.ANILIST -> excludedGenres.remove(genre)
                        }
                        updateGenreChips()
                        // Reset ViewModel page and reload results
                        viewModel.resetPage()
                        currentAniListPage = 1
                        loadResults()
                    }
                }
                chipGroup.addView(chip)
            }
            
            android.util.Log.d("GENRE_CHIPS_DEBUG", "Added all chips to group, total chips now: ${chipGroup.childCount}")
            
            // Calculate estimate based on chip visibility
            val tagsVisible = selectedTags.isNotEmpty() && selectedProvider == FilterProvider.ANILIST // Tags only for AniList
            val chipRowHeight = if (tagsVisible) 240 else 120 // 240 for both rows, 120 for single row
            val baseHeight = 272 // Base height of top bar without chips
            val estimatedHeight = baseHeight + chipRowHeight
            updateResultsPadding(estimatedHeight)
            android.util.Log.d("GENRE_CHIPS_DEBUG", "Genre chips visible, chipRowHeight=$chipRowHeight")
        } else {
            android.util.Log.d("GENRE_CHIPS_DEBUG", "Hiding genre chips - no genres found")
            chipGroup.visibility = View.GONE
            // Calculate estimate based on chip visibility
            val tagsVisible = selectedTags.isNotEmpty() && selectedProvider == FilterProvider.ANILIST // Tags only for AniList
            val chipRowHeight = if (tagsVisible) 120 else 0 // 120 if tags still visible, 0 if both gone
            val baseHeight = 272 // Base height of top bar without chips
            val estimatedHeight = baseHeight + chipRowHeight
            updateResultsPadding(estimatedHeight)
        }
        android.util.Log.d("GENRE_CHIPS_DEBUG", "========== updateGenreChips completed ==========")
    }

    private fun updateTagsChips() {
        binding?.tagsChips?.removeAllViews()
        val chipGroup = binding?.tagsChips ?: return

        // Tags only available for AniList
        val allTags = selectedTags + excludedTags
        
        if (selectedProvider == FilterProvider.ANILIST && allTags.isNotEmpty()) {
            chipGroup.visibility = View.VISIBLE
            
            // Add included tags first (normal styling)
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
                        loadResults()
                    }
                }
                chipGroup.addView(chip)
            }
            
            // Add excluded tags with grey styling and line-through
            excludedTags.forEach { tag ->
                val chip = com.google.android.material.chip.Chip(requireContext(), null, R.style.ChipFilled).apply {
                    text = tag
                    isCloseIconVisible = true
                    alpha = 0.5f // Low opacity for excluded chips
                    chipBackgroundColor = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
                    chipStrokeColor = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
                    chipStrokeWidth = 1f
                    setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.white))
                    paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG // Line through effect
                    setOnCloseIconClickListener {
                        excludedTags.remove(tag)
                        updateTagsChips()
                        // Reset ViewModel page and reload results
                        viewModel.resetPage()
                        currentAniListPage = 1
                        loadResults()
                    }
                }
                chipGroup.addView(chip)
            }
            
            // Calculate estimate based on chip visibility
            val genresVisible = selectedGenres.isNotEmpty() || excludedGenres.isNotEmpty()
            val chipRowHeight = if (genresVisible) 240 else 120 // 240 for both rows, 120 for single row
            val baseHeight = 272 // Base height of top bar without chips
            val estimatedHeight = baseHeight + chipRowHeight
            updateResultsPadding(estimatedHeight)
        } else {
            chipGroup.visibility = View.GONE
            // Calculate estimate based on chip visibility (tags only for AniList)
            val genresVisible = when (selectedProvider) {
                FilterProvider.TMDB -> selectedTmdbGenres.isNotEmpty()
                FilterProvider.ANILIST -> selectedGenres.isNotEmpty()
            }
            val chipRowHeight = if (genresVisible) 120 else 0 // Tags are gone, only check genres
            val baseHeight = 272 // Base height of top bar without chips
            val estimatedHeight = baseHeight + chipRowHeight
            updateResultsPadding(estimatedHeight)
        }
    }

    /**
     * Unified entry point for showing filter dialog.
     * Loads the appropriate dialog based on current provider.
     */
    private fun showFilterDialog() {
        when (selectedProvider) {
            FilterProvider.ANILIST -> showAniListFilterDialog()
            FilterProvider.TMDB -> showTmdbFilterDialog()
        }
    }

    /**
     * Shows the AniList-specific filter dialog.
     * Modular - only contains AniList filters.
     */
    private fun showAniListFilterDialog() {
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
        var dialogProvider = selectedProvider

        activity?.let { ctx ->
            val dialog = AlertDialog.Builder(ctx, R.style.AlertDialogCustom).create()
            val dialogBinding = com.lagradost.cloudstream3.databinding.BottomAnilistGenreTagSelectorBinding.inflate(
                dialog.layoutInflater,
                null,
                false
            )
            dialog.setView(dialogBinding.root)

            
            // Set initial accordion visibility based on current provider
            updateFilterDialogVisibility(dialogBinding, selectedProvider)

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
                    loadResults()

                    // SCROLL_POSITION_FIX: Don't scroll to top when loading defaults, preserve scroll position
                    // binding?.browseResults?.scrollToPosition(0)

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
                android.util.Log.d("STATE_SYNC_DEBUG", "STATE_SYNC_DEBUG: Provider changing from $selectedProvider to $dialogProvider")
                
                // Update class-level variables
                selectedProvider = dialogProvider
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
                android.util.Log.d("STATE_SYNC_FIX", "Creating filterState with: provider=$selectedProvider, genres=$selectedGenres, excludedGenres=$excludedGenres, tags=$selectedTags, excludedTags=$excludedTags")
                val filterState = BrowseFilterState(
                    provider = selectedProvider,
                    genres = selectedGenres,
                    tags = selectedTags,
                    excludedGenres = excludedGenres,
                    excludedTags = excludedTags,
                    year = selectedYear,
                    season = selectedSeason,
                    format = selectedFormat,
                    sort = selectedSort,
                    tmdbFormat = selectedTmdbFormat,
                    tmdbGenres = selectedTmdbGenres,
                    tmdbExcludedGenres = excludedTmdbGenres,
                    tmdbYear = selectedTmdbYear,
                    tmdbCountry = selectedTmdbCountry,
                    tmdbProvider = selectedTmdbProvider,
                    tmdbTrending = selectedTmdbTrending,
                    tmdbIncludeAdult = selectedTmdbIncludeAdult
                )
                android.util.Log.d("STATE_SYNC_FIX", "Calling viewModel.updateFilters with filterState=$filterState")
                viewModel.updateFilters(filterState)
                
                android.util.Log.d("STATE_SYNC_DEBUG", "STATE_SYNC_DEBUG: After ViewModel update - ViewModel filters=${viewModel.uiState.value?.filters}")
                android.util.Log.d("STATE_SYNC_FIX", "========== Filter state sync completed ==========")

                // Reset page to 1 when provider or filters change
                if (viewModel.uiState.value?.filters?.shouldResetPaging(filterState) == true) {
                    android.util.Log.d("STATE_SYNC_DEBUG", "Provider or format changed - resetting page to 1")
                    currentAniListPage = 1
                    viewModel.resetPage()
                }

                // Reload results with new filters
                loadResults()
                dialog.dismiss()
            }

            dialog.show()
        }
    }

    /**
     * Shows the TMDB-specific filter dialog.
     * Modular - only contains TMDB filters.
     */
    private fun showTmdbFilterDialog() {
        // TMDB-specific filter lists
        val formats = TmdbFilterUtils.FORMATS
        val years = TmdbFilterUtils.YEARS
        val countries = TmdbFilterUtils.COUNTRY_NAMES
        val providers = TmdbFilterUtils.PROVIDER_NAMES
        val trendingWindows = TmdbFilterUtils.TRENDING_DISPLAY_NAMES
        val sortOptions = TmdbFilterUtils.SORT_DISPLAY_NAMES

        // Dialog-level filter states
        var dialogFormat = if (selectedTmdbFormat == TmdbFormat.MOVIE) "Movie" else "TV Show"
        var dialogGenres = selectedTmdbGenres.toMutableSet()
        var dialogExcludedGenres = excludedTmdbGenres.toMutableSet()
        var dialogYear = selectedTmdbYear
        var dialogCountry = selectedTmdbCountry
        var dialogProvider = selectedTmdbProvider
        var dialogTrending = selectedTmdbTrending
        var dialogIncludeAdult = selectedTmdbIncludeAdult
        var dialogSort = syncSortValueForProvider(selectedSort, FilterProvider.TMDB)
        var dialogKeywords = selectedTmdbKeywords
        var dialogMinVotes = selectedTmdbMinVotes

        android.util.Log.d("TMDB_FILTER_DEBUG", "========== showTmdbFilterDialog START ==========")
        android.util.Log.d("TMDB_FILTER_DEBUG", "Initial state: format=$dialogFormat, year=$dialogYear, country=$dialogCountry")
        android.util.Log.d("TMDB_FILTER_DEBUG", "Initial state: provider=$dialogProvider, trending=$dialogTrending, sort=$dialogSort")
        android.util.Log.d("TMDB_FILTER_DEBUG", "Initial state: genres=$dialogGenres, excludedGenres=$dialogExcludedGenres")
        android.util.Log.d("TMDB_FILTER_DEBUG", "Fragment state: selectedTmdbFormat=$selectedTmdbFormat, selectedTmdbYear=$selectedTmdbYear")
        android.util.Log.d("TMDB_FILTER_DEBUG", "Fragment state: selectedTmdbCountry=$selectedTmdbCountry, selectedTmdbProvider=$selectedTmdbProvider")

        activity?.let { ctx ->
            val dialog = AlertDialog.Builder(ctx, R.style.AlertDialogCustom).create()
            val dialogBinding = com.lagradost.cloudstream3.databinding.BottomTmdbFilterBinding.inflate(
                dialog.layoutInflater,
                null,
                false
            )
            dialog.setView(dialogBinding.root)

            // Initialize accordion subtexts with current fragment state
            dialogBinding.tmdbFormatCount.text = dialogFormat
            dialogBinding.tmdbGenresCount.text = (dialogGenres.size + dialogExcludedGenres.size).toString()
            dialogBinding.tmdbYearCount.text = dialogYear
            dialogBinding.tmdbCountryCount.text = dialogCountry
            dialogBinding.tmdbProviderCount.text = dialogProvider
            dialogBinding.tmdbTrendingCount.text = dialogTrending
            dialogBinding.tmdbSortCount.text = dialogSort
            dialogBinding.tmdbKeywordCount.text = if (dialogKeywords.isBlank()) "None" else dialogKeywords
            dialogBinding.tmdbMinVotesCount.text = dialogMinVotes.toString()
            
            // Check if user is in search mode (main search bar has text)
            val isSearchMode = !searchQuery.isNullOrBlank()
            android.util.Log.d("SEARCH_FILTER_DEBUG", "showTmdbFilterDialog: searchQuery='$searchQuery', isSearchMode=$isSearchMode")
            
            // Initialize filter states based on current trending selection and search mode
            val isTrending = dialogTrending != "Off"
            updateFilterStatesForTrending(isTrending, dialogBinding)
            
            // If in search mode, disable all filter sections except keywords
            if (isSearchMode) {
                android.util.Log.d("SEARCH_FILTER_DEBUG", "showTmdbFilterDialog: Disabling filters due to search mode")
                
                // Disable all sections except keywords
                dialogBinding.tmdbFormatHeader.alpha = 0.5f
                dialogBinding.tmdbFormatRecycler.alpha = 0.5f
                dialogBinding.tmdbFormatHeader.isEnabled = false
                
                dialogBinding.tmdbGenresHeader.alpha = 0.5f
                dialogBinding.tmdbGenresRecycler.alpha = 0.5f
                dialogBinding.tmdbGenresHeader.isEnabled = false
                
                dialogBinding.tmdbYearHeader.alpha = 0.5f
                dialogBinding.tmdbYearRecycler.alpha = 0.5f
                dialogBinding.tmdbYearHeader.isEnabled = false
                
                dialogBinding.tmdbCountryHeader.alpha = 0.5f
                dialogBinding.tmdbCountryRecycler.alpha = 0.5f
                dialogBinding.tmdbCountryHeader.isEnabled = false
                
                dialogBinding.tmdbProviderHeader.alpha = 0.5f
                dialogBinding.tmdbProviderRecycler.alpha = 0.5f
                dialogBinding.tmdbProviderHeader.isEnabled = false
                
                dialogBinding.tmdbSortHeader.alpha = 0.5f
                dialogBinding.tmdbSortRecycler.alpha = 0.5f
                dialogBinding.tmdbSortHeader.isEnabled = false
                
                dialogBinding.tmdbMinVotesHeader.alpha = 0.5f
                dialogBinding.tmdbMinVotesRecycler.alpha = 0.5f
                dialogBinding.tmdbMinVotesHeader.isEnabled = false
                
                dialogBinding.tmdbAdultHeader.alpha = 0.5f
                dialogBinding.tmdbAdultToggle.alpha = 0.5f
                dialogBinding.tmdbAdultToggle.isEnabled = false
                
                // Keep keywords enabled - they work in discover mode
                dialogBinding.tmdbKeywordHeader.alpha = 1.0f
                dialogBinding.tmdbKeywordInputLayout.alpha = 1.0f
                dialogBinding.tmdbKeywordHeader.isEnabled = true
                dialogBinding.tmdbKeywordInput.isEnabled = true
                
                // Show message to user
                dialogBinding.tmdbKeywordCount.text = "Filters disabled during text search"
            }

            
            // Setup Format (Movie/TV) - Radio mode
            var formatAdapter: AniListFilterUtils.AniListCheckboxAdapter? = null
            formatAdapter = AniListFilterUtils.AniListCheckboxAdapter(
                formats,
                setOf(dialogFormat),
                emptySet(),
                { item, state ->
                    android.util.Log.d("TMDB_FILTER_DEBUG", "FORMAT_CALLBACK: item=$item, state=$state")
                    if (state == 1) {
                        dialogFormat = item
                        dialogBinding.tmdbFormatCount.text = item
                        // Update genre list based on format
                        updateTmdbGenreAdapter(dialogBinding, item, dialogGenres, dialogExcludedGenres, dialogYear, dialogCountry, dialogProvider, dialogTrending, dialogIncludeAdult, dialogSort, dialogMinVotes)
                        // For radio mode, update the selected set to update all items
                        val newSelectedSet = setOf(dialogFormat)
                        dialogBinding.tmdbFormatRecycler.post {
                            formatAdapter?.updateSelectedSet(newSelectedSet)
                        }
                        // Update TMDB Load Defaults button visibility
                        updateTmdbLoadDefaultsButtonVisibility(
                            dialogBinding, dialogFormat, dialogGenres, dialogExcludedGenres, dialogYear,
                            dialogCountry, dialogProvider, dialogTrending, dialogIncludeAdult, dialogSort,
                            dialogMinVotes
                        )
                    }
                },
                radioMode = true
            )
            dialogBinding.tmdbFormatRecycler.adapter = formatAdapter
            dialogBinding.tmdbFormatRecycler.layoutManager = LinearLayoutManager(ctx)
            dialogBinding.tmdbFormatRecycler.itemAnimator = null

            // Format accordion toggle
            dialogBinding.tmdbFormatHeader.setOnClickListener {
                toggleAccordion(dialogBinding.tmdbFormatRecycler, dialogBinding.tmdbFormatExpandIcon)
            }

            // Setup Genres - 3-state support
            updateTmdbGenreAdapter(dialogBinding, dialogFormat, dialogGenres, dialogExcludedGenres, dialogYear, dialogCountry, dialogProvider, dialogTrending, dialogIncludeAdult, dialogSort, dialogMinVotes)

            // Genres accordion toggle
            dialogBinding.tmdbGenresHeader.setOnClickListener {
                toggleAccordion(dialogBinding.tmdbGenresRecycler, dialogBinding.tmdbGenresExpandIcon)
            }

            // Setup Year - Radio mode
            var yearAdapter: AniListFilterUtils.AniListCheckboxAdapter? = null
            yearAdapter = AniListFilterUtils.AniListCheckboxAdapter(
                years,
                setOf(dialogYear),
                emptySet(),
                { item, state ->
                    android.util.Log.d("TMDB_FILTER_DEBUG", "YEAR_CALLBACK: item=$item, state=$state")
                    if (state == 1) {
                        dialogYear = item
                        dialogBinding.tmdbYearCount.text = item
                        // For radio mode, update the selected set to update all items
                        val newSelectedSet = setOf(dialogYear)
                        dialogBinding.tmdbYearRecycler.post {
                            yearAdapter?.updateSelectedSet(newSelectedSet)
                        }
                        // Update TMDB Load Defaults button visibility
                        updateTmdbLoadDefaultsButtonVisibility(
                            dialogBinding, dialogFormat, dialogGenres, dialogExcludedGenres, dialogYear,
                            dialogCountry, dialogProvider, dialogTrending, dialogIncludeAdult, dialogSort,
                            dialogMinVotes
                        )
                    }
                },
                radioMode = true
            )
            dialogBinding.tmdbYearRecycler.adapter = yearAdapter
            dialogBinding.tmdbYearRecycler.layoutManager = LinearLayoutManager(ctx)
            dialogBinding.tmdbYearRecycler.itemAnimator = null

            // Year accordion toggle
            dialogBinding.tmdbYearHeader.setOnClickListener {
                toggleAccordion(dialogBinding.tmdbYearRecycler, dialogBinding.tmdbYearExpandIcon)
            }

            // Setup Season - Radio mode (initially disabled if year is "All")
            // Setup Country - Radio mode
            var countryAdapter: AniListFilterUtils.AniListCheckboxAdapter? = null
            countryAdapter = AniListFilterUtils.AniListCheckboxAdapter(
                countries,
                setOf(dialogCountry),
                emptySet(),
                { item, state ->
                    android.util.Log.d("TMDB_FILTER_DEBUG", "COUNTRY_CALLBACK: item=$item, state=$state")
                    if (state == 1) {
                        dialogCountry = item
                        dialogBinding.tmdbCountryCount.text = item
                        // For radio mode, update the selected set to update all items
                        val newSelectedSet = setOf(dialogCountry)
                        dialogBinding.tmdbCountryRecycler.post {
                            countryAdapter?.updateSelectedSet(newSelectedSet)
                        }
                        // Update TMDB Load Defaults button visibility
                        updateTmdbLoadDefaultsButtonVisibility(
                            dialogBinding, dialogFormat, dialogGenres, dialogExcludedGenres, dialogYear,
                            dialogCountry, dialogProvider, dialogTrending, dialogIncludeAdult, dialogSort,
                            dialogMinVotes
                        )
                    }
                },
                radioMode = true
            )
            dialogBinding.tmdbCountryRecycler.adapter = countryAdapter
            dialogBinding.tmdbCountryRecycler.layoutManager = LinearLayoutManager(ctx)
            dialogBinding.tmdbCountryRecycler.itemAnimator = null

            // Country accordion toggle
            dialogBinding.tmdbCountryHeader.setOnClickListener {
                toggleAccordion(dialogBinding.tmdbCountryRecycler, dialogBinding.tmdbCountryExpandIcon)
            }

            // Setup Keywords with simple text input
            dialogBinding.tmdbKeywordHeader.setOnClickListener {
                val isVisible = dialogBinding.tmdbKeywordInputLayout.visibility == View.VISIBLE
                if (isVisible) {
                    dialogBinding.tmdbKeywordInputLayout.visibility = View.GONE
                    dialogBinding.tmdbKeywordExpandIcon.setImageResource(R.drawable.ic_baseline_arrow_forward_24)
                } else {
                    dialogBinding.tmdbKeywordInputLayout.visibility = View.VISIBLE
                    dialogBinding.tmdbKeywordExpandIcon.setImageResource(R.drawable.ic_baseline_keyboard_arrow_down_24)
                    // Focus on keyword input when opened
                    dialogBinding.tmdbKeywordInput.requestFocus()
                }
            }

            // Set up simple text input
            dialogBinding.tmdbKeywordInput.setText(dialogKeywords)
            dialogBinding.tmdbKeywordInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    dialogKeywords = s.toString().trim()
                    dialogBinding.tmdbKeywordCount.text = if (dialogKeywords.isBlank()) "None" else dialogKeywords
                }
            })

            // Setup Trending - Radio mode
            var trendingAdapter: AniListFilterUtils.AniListCheckboxAdapter? = null
            trendingAdapter = AniListFilterUtils.AniListCheckboxAdapter(
                trendingWindows,
                setOf(dialogTrending),
                emptySet(),
                { item, state ->
                    android.util.Log.d("TMDB_FILTER_DEBUG", "TRENDING_CALLBACK: item=$item, state=$state")
                    if (state == 1) {
                        dialogTrending = item
                        dialogBinding.tmdbTrendingCount.text = item
                        // For radio mode, update the selected set to update all items
                        val newSelectedSet = setOf(dialogTrending)
                        dialogBinding.tmdbTrendingRecycler.post {
                            trendingAdapter?.updateSelectedSet(newSelectedSet)
                        }
                        
                        // When Trending is selected, disable other filters as per hsp1020's feedback
                        val isTrending = item != "Off"
                        updateFilterStatesForTrending(isTrending, dialogBinding)
                        
                        // Update TMDB Load Defaults button visibility
                        updateTmdbLoadDefaultsButtonVisibility(
                            dialogBinding, dialogFormat, dialogGenres, dialogExcludedGenres, dialogYear,
                            dialogCountry, dialogProvider, dialogTrending, dialogIncludeAdult, dialogSort,
                            dialogMinVotes
                        )
                    }
                },
                radioMode = true
            )
            dialogBinding.tmdbTrendingRecycler.adapter = trendingAdapter
            dialogBinding.tmdbTrendingRecycler.layoutManager = LinearLayoutManager(ctx)
            dialogBinding.tmdbTrendingRecycler.itemAnimator = null

            // Trending accordion toggle
            dialogBinding.tmdbTrendingHeader.setOnClickListener {
                toggleAccordion(dialogBinding.tmdbTrendingRecycler, dialogBinding.tmdbTrendingExpandIcon)
            }

            // Setup Provider (Streaming On) - Radio mode
            var streamingProviderAdapter: AniListFilterUtils.AniListCheckboxAdapter? = null
            streamingProviderAdapter = AniListFilterUtils.AniListCheckboxAdapter(
                providers,
                setOf(dialogProvider),
                emptySet(),
                { item, state ->
                    android.util.Log.d("TMDB_FILTER_DEBUG", "PROVIDER_CALLBACK: item=$item, state=$state")
                    if (state == 1) {
                        dialogProvider = item
                        dialogBinding.tmdbProviderCount.text = item
                        // For radio mode, update the selected set to update all items
                        val newSelectedSet = setOf(dialogProvider)
                        dialogBinding.tmdbProviderRecycler.post {
                            streamingProviderAdapter?.updateSelectedSet(newSelectedSet)
                        }
                        // Update TMDB Load Defaults button visibility
                        updateTmdbLoadDefaultsButtonVisibility(
                            dialogBinding, dialogFormat, dialogGenres, dialogExcludedGenres, dialogYear,
                            dialogCountry, dialogProvider, dialogTrending, dialogIncludeAdult, dialogSort,
                            dialogMinVotes
                        )
                    }
                },
                radioMode = true
            )
            dialogBinding.tmdbProviderRecycler.adapter = streamingProviderAdapter
            dialogBinding.tmdbProviderRecycler.layoutManager = LinearLayoutManager(ctx)
            dialogBinding.tmdbProviderRecycler.itemAnimator = null

            // Provider accordion toggle
            dialogBinding.tmdbProviderHeader.setOnClickListener {
                toggleAccordion(dialogBinding.tmdbProviderRecycler, dialogBinding.tmdbProviderExpandIcon)
            }

            // Setup Sort - Radio mode
            var sortAdapter: AniListFilterUtils.AniListCheckboxAdapter? = null
            sortAdapter = AniListFilterUtils.AniListCheckboxAdapter(
                sortOptions,
                setOf(dialogSort),
                emptySet(),
                { item, state ->
                    android.util.Log.d("TMDB_FILTER_DEBUG", "SORT_CALLBACK: item=$item, state=$state")
                    if (state == 1) {
                        dialogSort = item
                        dialogBinding.tmdbSortCount.text = item
                        // For radio mode, update the selected set to update all items
                        val newSelectedSet = setOf(dialogSort)
                        dialogBinding.tmdbSortRecycler.post {
                            sortAdapter?.updateSelectedSet(newSelectedSet)
                        }
                        // Update TMDB Load Defaults button visibility
                        updateTmdbLoadDefaultsButtonVisibility(
                            dialogBinding, dialogFormat, dialogGenres, dialogExcludedGenres, dialogYear,
                            dialogCountry, dialogProvider, dialogTrending, dialogIncludeAdult, dialogSort,
                            dialogMinVotes
                        )
                    }
                },
                radioMode = true
            )
            dialogBinding.tmdbSortRecycler.adapter = sortAdapter
            dialogBinding.tmdbSortRecycler.layoutManager = LinearLayoutManager(ctx)
            dialogBinding.tmdbSortRecycler.itemAnimator = null

            // Sort accordion toggle
            dialogBinding.tmdbSortHeader.setOnClickListener {
                toggleAccordion(dialogBinding.tmdbSortRecycler, dialogBinding.tmdbSortExpandIcon)
            }

            // Setup Minimum Votes - Radio mode
            val minVotesOptions = listOf("0", "1", "5", "10", "20", "30", "50", "100", "200", "300", "400", "500", "1000", "2000", "3000", "5000", "10000")
            var minVotesAdapter: AniListFilterUtils.AniListCheckboxAdapter? = null
            minVotesAdapter = AniListFilterUtils.AniListCheckboxAdapter(
                minVotesOptions,
                setOf(dialogMinVotes.toString()),
                emptySet(),
                { item, state ->
                    android.util.Log.d("TMDB_FILTER_DEBUG", "MIN_VOTES_CALLBACK: item=$item, state=$state")
                    if (state == 1) {
                        dialogMinVotes = item.toIntOrNull() ?: 0
                        dialogBinding.tmdbMinVotesCount.text = item
                        // For radio mode, update the selected set to update all items
                        val newSelectedSet = setOf(item)
                        dialogBinding.tmdbMinVotesRecycler.post {
                            minVotesAdapter?.updateSelectedSet(newSelectedSet)
                        }
                        // Update TMDB Load Defaults button visibility
                        updateTmdbLoadDefaultsButtonVisibility(
                            dialogBinding, dialogFormat, dialogGenres, dialogExcludedGenres, dialogYear,
                            dialogCountry, dialogProvider, dialogTrending, dialogIncludeAdult, dialogSort,
                            dialogMinVotes
                        )
                    }
                },
                radioMode = true
            )
            dialogBinding.tmdbMinVotesRecycler.adapter = minVotesAdapter
            dialogBinding.tmdbMinVotesRecycler.layoutManager = LinearLayoutManager(ctx)
            dialogBinding.tmdbMinVotesRecycler.itemAnimator = null

            // Minimum Votes accordion toggle
            dialogBinding.tmdbMinVotesHeader.setOnClickListener {
                toggleAccordion(dialogBinding.tmdbMinVotesRecycler, dialogBinding.tmdbMinVotesExpandIcon)
            }

            // Setup Adult Content Toggle
            dialogBinding.tmdbAdultToggle.isChecked = dialogIncludeAdult
            dialogBinding.tmdbAdultToggle.setOnCheckedChangeListener { _, isChecked ->
                android.util.Log.d("TMDB_FILTER_DEBUG", "ADULT_TOGGLE: isChecked=$isChecked")
                dialogIncludeAdult = isChecked
                // Update TMDB Load Defaults button visibility
                updateTmdbLoadDefaultsButtonVisibility(
                    dialogBinding, dialogFormat, dialogGenres, dialogExcludedGenres, dialogYear,
                    dialogCountry, dialogProvider, dialogTrending, dialogIncludeAdult, dialogSort,
                    dialogMinVotes
                )
            }

            // Clear button
            dialogBinding.tmdbClearButton.setOnClickListener {
                android.util.Log.d("TMDB_FILTER_DEBUG", "CLEAR_BUTTON: Clearing all filters")
                dialogFormat = "Movie"
                dialogGenres.clear()
                dialogExcludedGenres.clear()
                dialogYear = "All"
                dialogCountry = "All"
                dialogProvider = "All"
                dialogTrending = "Off"
                dialogIncludeAdult = false
                dialogSort = "Popularity (High to Low)"
                dialogMinVotes = 0 // MIN_VOTES_RESET_FIX: Reset minimum votes
                dialogKeywords = ""

                // Update UI counts
                dialogBinding.tmdbFormatCount.text = dialogFormat
                dialogBinding.tmdbGenresCount.text = "0"
                dialogBinding.tmdbYearCount.text = dialogYear
                dialogBinding.tmdbCountryCount.text = dialogCountry
                dialogBinding.tmdbProviderCount.text = dialogProvider
                dialogBinding.tmdbTrendingCount.text = dialogTrending
                dialogBinding.tmdbSortCount.text = dialogSort
                dialogBinding.tmdbAdultToggle.isChecked = false
                dialogBinding.tmdbMinVotesCount.text = "0" // MIN_VOTES_RESET_FIX: Update UI count
                dialogBinding.tmdbKeywordInput.setText("")
                dialogBinding.tmdbKeywordCount.text = "None"

                // FILTER_CHECKMARKS_FIX: Reset all adapters including missing minVotesAdapter
                formatAdapter.updateSelectedSet(setOf(dialogFormat))
                updateTmdbGenreAdapter(dialogBinding, dialogFormat, dialogGenres, dialogExcludedGenres, dialogYear, dialogCountry, dialogProvider, dialogTrending, dialogIncludeAdult, dialogSort, dialogMinVotes)
                yearAdapter.updateSelectedSet(setOf(dialogYear))
                countryAdapter.updateSelectedSet(setOf(dialogCountry))
                trendingAdapter.updateSelectedSet(setOf(dialogTrending))
                streamingProviderAdapter.updateSelectedSet(setOf(dialogProvider))
                sortAdapter.updateSelectedSet(setOf(dialogSort))
                minVotesAdapter.updateSelectedSet(setOf("0")) // Reset minimum votes to 0
                android.util.Log.d("FILTER_CHECKMARKS_FIX", "Cleared all TMDB filter checkmarks including minVotes")
                
                // Update TMDB Load Defaults button visibility
                updateTmdbLoadDefaultsButtonVisibility(
                    dialogBinding, dialogFormat, dialogGenres, dialogExcludedGenres, dialogYear,
                    dialogCountry, dialogProvider, dialogTrending, dialogIncludeAdult, dialogSort,
                    dialogMinVotes
                )
            }

            // SET_DEFAULT_TMDB_FIX: Add missing TMDB Set Default button click listener
            dialogBinding.setDefaultButton.setOnClickListener {
                android.util.Log.d("SET_DEFAULT_TMDB_FIX", "TMDB Set Default button clicked")
                android.util.Log.d("SET_DEFAULT_TMDB_FIX", "Current filter values: format=$dialogFormat, year=$dialogYear, country=$dialogCountry, sort=$dialogSort")
                
                try {
                    android.util.Log.d("SET_DEFAULT_TMDB_FIX", "Creating confirmation dialog")
                    
                    val confirmDialog = android.app.AlertDialog.Builder(requireContext(), R.style.AlertDialogCustom)
                        .setTitle("Set TMDB Defaults")
                        .setMessage("Save current filter settings as default for TMDB?")
                        .setPositiveButton("Set TMDB Defaults") { _, _ ->
                            android.util.Log.d("SET_DEFAULT_TMDB_FIX", "User confirmed TMDB default settings")
                            
                            // Save TMDB defaults to SharedPreferences
                            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                            prefs.edit().apply {
                                putString("tmdb_default_format", dialogFormat)
                                putStringSet("tmdb_default_genres", dialogGenres)
                                putStringSet("tmdb_default_excluded_genres", dialogExcludedGenres)
                                putString("tmdb_default_year", dialogYear)
                                putString("tmdb_default_country", dialogCountry)
                                putString("tmdb_default_provider", dialogProvider)
                                putString("tmdb_default_trending", dialogTrending)
                                putBoolean("tmdb_default_include_adult", dialogIncludeAdult)
                                putString("tmdb_default_sort", dialogSort)
                                putInt("tmdb_default_min_votes", dialogMinVotes)
                                putString("tmdb_default_keywords", dialogKeywords)
                                apply()
                            }
                            
                            android.util.Log.d("SET_DEFAULT_TMDB_FIX", "TMDB defaults saved successfully")
                            Toast.makeText(requireContext(), "TMDB defaults set", Toast.LENGTH_SHORT).show()
                            
                            // SET_DEFAULT_TMDB_FIX: Apply defaults and reload results
                            dialogFormat = prefs.getString("tmdb_default_format", "Movie") ?: "Movie"
                            dialogYear = prefs.getString("tmdb_default_year", "All") ?: "All"
                            dialogCountry = prefs.getString("tmdb_default_country", "All") ?: "All"
                            dialogSort = prefs.getString("tmdb_default_sort", "Popularity (High to Low)") ?: "Popularity (High to Low)"
                            dialogMinVotes = prefs.getInt("tmdb_default_min_votes", 0)
                            dialogKeywords = prefs.getString("tmdb_default_keywords", "") ?: ""
                            
                            // Update UI to show applied defaults
                            dialogBinding.tmdbFormatCount.text = dialogFormat
                            dialogBinding.tmdbGenresCount.text = (dialogGenres.size + dialogExcludedGenres.size).toString()
                            dialogBinding.tmdbYearCount.text = dialogYear
                            dialogBinding.tmdbCountryCount.text = dialogCountry
                            dialogBinding.tmdbProviderCount.text = dialogProvider
                            dialogBinding.tmdbTrendingCount.text = dialogTrending
                            dialogBinding.tmdbSortCount.text = dialogSort
                            dialogBinding.tmdbMinVotesCount.text = dialogMinVotes.toString()
                            dialogBinding.tmdbKeywordInput.setText(dialogKeywords)
                            dialogBinding.tmdbKeywordCount.text = if (dialogKeywords.isBlank()) "None" else dialogKeywords
                            formatAdapter.updateSelectedSet(setOf(dialogFormat))
                            yearAdapter.updateSelectedSet(setOf(dialogYear))
                            countryAdapter.updateSelectedSet(setOf(dialogCountry))
                            trendingAdapter.updateSelectedSet(setOf(dialogTrending))
                            streamingProviderAdapter.updateSelectedSet(setOf(dialogProvider))
                            sortAdapter.updateSelectedSet(setOf(dialogSort))
                            minVotesAdapter.updateSelectedSet(setOf(dialogMinVotes.toString()))
                            
                            android.util.Log.d("SET_DEFAULT_TMDB_FIX", "Applied defaults to UI: format=$dialogFormat, year=$dialogYear, country=$dialogCountry, sort=$dialogSort")
                            
                            // Apply to fragment state immediately so Set Default has effect now.
                            selectedTmdbFormat = if (dialogFormat == "Movie") TmdbFormat.MOVIE else TmdbFormat.TV
                            selectedTmdbGenres.clear()
                            selectedTmdbGenres.addAll(dialogGenres)
                            excludedTmdbGenres.clear()
                            excludedTmdbGenres.addAll(dialogExcludedGenres)
                            selectedTmdbYear = dialogYear
                            selectedTmdbCountry = dialogCountry
                            selectedTmdbProvider = dialogProvider
                            selectedTmdbTrending = dialogTrending
                            selectedTmdbIncludeAdult = dialogIncludeAdult
                            selectedSort = dialogSort
                            selectedTmdbMinVotes = dialogMinVotes
                                                        currentAniListPage = 1
                            viewModel.resetPage()
                            loadResults()
                        }
                        .setNegativeButton("Cancel") { _, _ ->
                            android.util.Log.d("SET_DEFAULT_TMDB_FIX", "User cancelled TMDB default settings")
                        }
                        .create()
                    
                    android.util.Log.d("SET_DEFAULT_TMDB_FIX", "About to show confirmation dialog")
                    confirmDialog.show()
                    android.util.Log.d("SET_DEFAULT_TMDB_FIX", "Confirmation dialog shown successfully")
                } catch (e: Exception) {
                    android.util.Log.e("SET_DEFAULT_TMDB_FIX", "Error showing confirmation dialog", e)
                    Toast.makeText(requireContext(), "Error setting defaults", Toast.LENGTH_SHORT).show()
                }
            }

            // Set initial TMDB Load Defaults button visibility
            updateTmdbLoadDefaultsButtonVisibility(
                dialogBinding, dialogFormat, dialogGenres, dialogExcludedGenres, dialogYear,
                dialogCountry, dialogProvider, dialogTrending, dialogIncludeAdult, dialogSort,
                dialogMinVotes
            )

            // TMDB Load Defaults button
            dialogBinding.loadDefaultButton.setOnClickListener {
                android.util.Log.d("BrowseFragment", "TMDB_LOAD_DEFAULTS_DEBUG: TMDB Load Defaults button clicked")
                val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                
                dialogFormat = prefs.getString("tmdb_default_format", "Movie") ?: "Movie"
                dialogGenres = prefs.getStringSet("tmdb_default_genres", emptySet())?.toMutableSet() ?: mutableSetOf()
                dialogExcludedGenres = prefs.getStringSet("tmdb_default_excluded_genres", emptySet())?.toMutableSet() ?: mutableSetOf()
                dialogYear = prefs.getString("tmdb_default_year", "All") ?: "All"
                dialogCountry = prefs.getString("tmdb_default_country", "All") ?: "All"
                dialogProvider = prefs.getString("tmdb_default_provider", "All") ?: "All"
                dialogTrending = prefs.getString("tmdb_default_trending", "Off") ?: "Off"
                dialogIncludeAdult = prefs.getBoolean("tmdb_default_include_adult", false)
                dialogSort = prefs.getString("tmdb_default_sort", "Popularity (High to Low)") ?: "Popularity (High to Low)"
                dialogMinVotes = prefs.getInt("tmdb_default_min_votes", 0)
                dialogKeywords = prefs.getString("tmdb_default_keywords", "") ?: ""

                dialogBinding.tmdbFormatCount.text = dialogFormat
                dialogBinding.tmdbGenresCount.text = (dialogGenres.size + dialogExcludedGenres.size).toString()
                dialogBinding.tmdbYearCount.text = dialogYear
                dialogBinding.tmdbCountryCount.text = dialogCountry
                dialogBinding.tmdbProviderCount.text = dialogProvider
                dialogBinding.tmdbTrendingCount.text = dialogTrending
                dialogBinding.tmdbSortCount.text = dialogSort
                dialogBinding.tmdbMinVotesCount.text = dialogMinVotes.toString()
                dialogBinding.tmdbAdultToggle.isChecked = dialogIncludeAdult
                dialogBinding.tmdbKeywordInput.setText(dialogKeywords)
                    dialogBinding.tmdbKeywordCount.text = if (dialogKeywords.isBlank()) "None" else dialogKeywords

                formatAdapter.updateSelectedSet(setOf(dialogFormat))
                updateTmdbGenreAdapter(dialogBinding, dialogFormat, dialogGenres, dialogExcludedGenres, dialogYear, dialogCountry, dialogProvider, dialogTrending, dialogIncludeAdult, dialogSort, dialogMinVotes)
                yearAdapter.updateSelectedSet(setOf(dialogYear))
                countryAdapter.updateSelectedSet(setOf(dialogCountry))
                trendingAdapter.updateSelectedSet(setOf(dialogTrending))
                streamingProviderAdapter.updateSelectedSet(setOf(dialogProvider))
                sortAdapter.updateSelectedSet(setOf(dialogSort))
                minVotesAdapter.updateSelectedSet(setOf(dialogMinVotes.toString()))
                updateFilterStatesForTrending(dialogTrending != "Off", dialogBinding)

                // Update TMDB Load Defaults button visibility
                updateTmdbLoadDefaultsButtonVisibility(
                    dialogBinding, dialogFormat, dialogGenres, dialogExcludedGenres, dialogYear,
                    dialogCountry, dialogProvider, dialogTrending, dialogIncludeAdult, dialogSort,
                    dialogMinVotes
                )

                Toast.makeText(requireContext(), "TMDB defaults loaded", Toast.LENGTH_SHORT).show()
            }

            // Apply button
            dialogBinding.tmdbApplyButton.setOnClickListener {
                android.util.Log.d("TMDB_FILTER_DEBUG", "========== APPLY_BUTTON START ==========")
                android.util.Log.d("TMDB_FILTER_DEBUG", "APPLY_BUTTON: dialogFormat=$dialogFormat, dialogYear=$dialogYear, dialogCountry=$dialogCountry")
                android.util.Log.d("TMDB_FILTER_DEBUG", "APPLY_BUTTON: dialogProvider=$dialogProvider, dialogTrending=$dialogTrending, dialogSort=$dialogSort")
                android.util.Log.d("TMDB_FILTER_DEBUG", "APPLY_BUTTON: dialogGenres=$dialogGenres, dialogExcludedGenres=$dialogExcludedGenres")
                android.util.Log.d("TMDB_FILTER_DEBUG", "APPLY_BUTTON: dialogIncludeAdult=$dialogIncludeAdult")
                
                // Update class-level variables
                android.util.Log.d("TMDB_FILTER_DEBUG", "APPLY_BUTTON: Updating fragment-level variables")
                selectedTmdbFormat = if (dialogFormat == "Movie") TmdbFormat.MOVIE else TmdbFormat.TV
                selectedTmdbGenres.clear()
                selectedTmdbGenres.addAll(dialogGenres)
                excludedTmdbGenres.clear()
                excludedTmdbGenres.addAll(dialogExcludedGenres)
                selectedTmdbYear = dialogYear
                selectedTmdbCountry = dialogCountry
                selectedTmdbProvider = dialogProvider
                selectedTmdbTrending = dialogTrending
                selectedTmdbIncludeAdult = dialogIncludeAdult
                selectedSort = dialogSort
                selectedTmdbKeywords = dialogKeywords
                selectedTmdbMinVotes = dialogMinVotes
                
                android.util.Log.d("TMDB_FILTER_DEBUG", "APPLY_BUTTON: After update - selectedTmdbFormat=$selectedTmdbFormat, selectedTmdbYear=$selectedTmdbYear")
                android.util.Log.d("TMDB_FILTER_DEBUG", "APPLY_BUTTON: After update - selectedTmdbCountry=$selectedTmdbCountry, selectedTmdbProvider=$selectedTmdbProvider")
                android.util.Log.d("TMDB_FILTER_DEBUG", "APPLY_BUTTON: After update - selectedTmdbGenres=$selectedTmdbGenres, selectedTmdbTrending=$selectedTmdbTrending")

                // Update ViewModel filter state
                val filterState = BrowseFilterState(
                    provider = FilterProvider.TMDB,
                    tmdbFormat = selectedTmdbFormat,
                    tmdbGenres = selectedTmdbGenres,
                    tmdbExcludedGenres = excludedTmdbGenres,
                    tmdbYear = selectedTmdbYear,
                    tmdbCountry = selectedTmdbCountry,
                    tmdbProvider = selectedTmdbProvider,
                    tmdbTrending = selectedTmdbTrending,
                    tmdbIncludeAdult = selectedTmdbIncludeAdult,
                    tmdbKeywords = selectedTmdbKeywords,
                    tmdbMinVotes = selectedTmdbMinVotes,
                    sort = selectedSort
                )
                android.util.Log.d("TMDB_FILTER_DEBUG", "APPLY_BUTTON: Calling viewModel.updateFilters")
                viewModel.updateFilters(filterState)

                // Reset page and reload AFTER ViewModel state is updated
                android.util.Log.d("TMDB_FILTER_DEBUG", "APPLY_BUTTON: Resetting page and calling loadResults()")
                viewModel.resetPage()
                currentAniListPage = 1
                
                // Post to ensure ViewModel update completes before loading results
                binding?.root?.post {
                    loadResults()
                }
                
                dialog.dismiss()
                android.util.Log.d("TMDB_FILTER_DEBUG", "========== APPLY_BUTTON END ==========")
            }

            dialog.show()
        }
    }

    /**
     * Updates filter states when Trending is selected.
     * When Trending is active, disable other filters as per hsp1020's feedback.
     */
    private fun updateFilterStatesForTrending(isTrending: Boolean, dialogBinding: com.lagradost.cloudstream3.databinding.BottomTmdbFilterBinding) {
        android.util.Log.d("TMDB_FILTER_DEBUG", "TRENDING_FILTER_UPDATE: isTrending=$isTrending")
        
        // Enable/disable filter sections based on trending state
        val isEnabled = !isTrending
        
        // Format section - always enabled (trending uses it to filter movie vs TV)
        
        // Genres section - disable when trending
        dialogBinding.tmdbGenresHeader.alpha = if (isEnabled) 1.0f else 0.5f
        dialogBinding.tmdbGenresRecycler.alpha = if (isEnabled) 1.0f else 0.5f
        dialogBinding.tmdbGenresHeader.isEnabled = isEnabled
        
        // Keywords section - disable when trending
        dialogBinding.tmdbKeywordHeader.alpha = if (isEnabled) 1.0f else 0.5f
        dialogBinding.tmdbKeywordInputLayout.alpha = if (isEnabled) 1.0f else 0.5f
        dialogBinding.tmdbKeywordHeader.isEnabled = isEnabled
        dialogBinding.tmdbKeywordInput.isEnabled = isEnabled
        
        // Year section - disable when trending
        dialogBinding.tmdbYearHeader.alpha = if (isEnabled) 1.0f else 0.5f
        dialogBinding.tmdbYearRecycler.alpha = if (isEnabled) 1.0f else 0.5f
        dialogBinding.tmdbYearHeader.isEnabled = isEnabled
        
        // Country section - disable when trending
        dialogBinding.tmdbCountryHeader.alpha = if (isEnabled) 1.0f else 0.5f
        dialogBinding.tmdbCountryRecycler.alpha = if (isEnabled) 1.0f else 0.5f
        dialogBinding.tmdbCountryHeader.isEnabled = isEnabled
        
        // Streaming provider section - disable when trending
        dialogBinding.tmdbProviderHeader.alpha = if (isEnabled) 1.0f else 0.5f
        dialogBinding.tmdbProviderRecycler.alpha = if (isEnabled) 1.0f else 0.5f
        dialogBinding.tmdbProviderHeader.isEnabled = isEnabled
        
        // Sort section - disable when trending (trending has its own sort)
        dialogBinding.tmdbSortHeader.alpha = if (isEnabled) 1.0f else 0.5f
        dialogBinding.tmdbSortRecycler.alpha = if (isEnabled) 1.0f else 0.5f
        dialogBinding.tmdbSortHeader.isEnabled = isEnabled
        
        // Minimum Votes section - disable when trending
        dialogBinding.tmdbMinVotesHeader.alpha = if (isEnabled) 1.0f else 0.5f
        dialogBinding.tmdbMinVotesRecycler.alpha = if (isEnabled) 1.0f else 0.5f
        dialogBinding.tmdbMinVotesHeader.isEnabled = isEnabled
        
        // Adult content toggle - disable when trending
        dialogBinding.tmdbAdultHeader.alpha = if (isEnabled) 1.0f else 0.5f
        dialogBinding.tmdbAdultToggle.alpha = if (isEnabled) 1.0f else 0.5f
        dialogBinding.tmdbAdultToggle.isEnabled = isEnabled
        
        android.util.Log.d("TMDB_FILTER_DEBUG", "TRENDING_FILTER_UPDATE: Filter states updated - enabled sections: $isEnabled")
    }

    /**
     * Updates filter states when user is searching vs browsing.
     * When searching (text in search bar), disable all filters since Search endpoint ignores them.
     * When browsing (empty search bar), enable filters based on trending state.
     */
    private fun updateFilterStatesForSearch(isSearchMode: Boolean) {
        android.util.Log.d("SEARCH_FILTER_DEBUG", "SEARCH_FILTER_UPDATE: isSearchMode=$isSearchMode, searchQuery='$searchQuery'")
        
        // TMDB_SEARCH_BUTTON_FIX: Double-check search state based on actual searchQuery
        val actualSearchMode = !searchQuery.isNullOrBlank()
        if (isSearchMode != actualSearchMode) {
            android.util.Log.w("TMDB_SEARCH_BUTTON_FIX", "Search mode mismatch! isSearchMode=$isSearchMode, actualSearchMode=$actualSearchMode")
        }
        
        // ANILIST_FILTER_DISABLE_FIX: Only disable filters for TMDB search, not AniList
        // AniList search supports filters, TMDB search doesn't
        val isTrending = selectedTmdbTrending != "Off"
        val isTmdbProvider = selectedProvider == FilterProvider.TMDB
        val shouldDisableFilters = (isSearchMode && isTmdbProvider) || isTrending
        android.util.Log.d("ANILIST_FILTER_DISABLE_FIX", "Filter disable logic - isSearchMode=$isSearchMode, isTmdbProvider=$isTmdbProvider, isTrending=$isTrending, shouldDisableFilters=$shouldDisableFilters")
        
        // FILTER_BUTTONS_FIX: Never disable the main filter button - users always need access to settings
        val shouldDisableFilterButton = false // Always keep filter button enabled
        android.util.Log.d("ANILIST_FILTER_DISABLE_FIX", "Filter button disable logic - shouldDisableFilterButton=$shouldDisableFilterButton")
        
        // Update main browse filter UI (genre chips, filter labels, etc.)
        binding?.apply {
            // Genre/Tags chips section
            genreChips.alpha = if (shouldDisableFilters) 0.5f else 1.0f
            tagsChips.alpha = if (shouldDisableFilters) 0.5f else 1.0f
            
            // Filter labels section
            yearLabel.alpha = if (shouldDisableFilters) 0.5f else 1.0f
            secondaryFilterLabel.alpha = if (shouldDisableFilters) 0.5f else 1.0f
            formatLabel.alpha = if (shouldDisableFilters) 0.5f else 1.0f
            sortLabel.alpha = if (shouldDisableFilters) 0.5f else 1.0f
            streamingProviderChip.alpha = if (shouldDisableFilters) 0.5f else 1.0f
            
            // TRENDING_BUTTON_FIX: Filter button remains enabled for settings access
            filterButton.alpha = if (shouldDisableFilterButton) 0.5f else 1.0f
            filterButton.isEnabled = !shouldDisableFilterButton
        }
        
        android.util.Log.d("SEARCH_FILTER_DEBUG", "SEARCH_FILTER_UPDATE: Filter states updated - disabled: $shouldDisableFilters")
    }

    /**
     * Show metadata language selection dialog
     */
    private fun showMetadataLanguageDialog() {
        val languages = listOf(
            "en-US" to "English (US)",
            "en-GB" to "English (UK)", 
            "ja" to "Japanese",
            "ko" to "Korean",
            "es" to "Spanish",
            "fr" to "French",
            "de" to "German",
            "it" to "Italian",
            "pt" to "Portuguese",
            "ru" to "Russian",
            "zh" to "Chinese",
            "hi" to "Hindi",
            "ar" to "Arabic"
        )
        
        val currentLanguage = com.lagradost.cloudstream3.syncproviders.providers.TmdbApi.getDisplayLanguage(requireContext())
        
        activity?.let { ctx ->
            val builder = androidx.appcompat.app.AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
            
            builder.setTitle("Select Metadata Language")
            
            // Create radio buttons for language selection
            val checkedItem = languages.map { it.first }.indexOf(currentLanguage).let { if (it >= 0) it else -1 }
            
            builder.setSingleChoiceItems(
                languages.map { it.second }.toTypedArray(),
                checkedItem
            ) { _, which ->
                val selectedLanguage = languages[which].first
                android.util.Log.d("METADATA_LANGUAGE", "Selected language: $selectedLanguage")
                
                // Save to SharedPreferences
                val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                prefs.edit().putString("tmdb_display_language_key", selectedLanguage).apply()
                
                // Update UI text
                binding?.metadataLanguageText?.text = languages.find { it.first == selectedLanguage }?.second ?: selectedLanguage
                
                // Reload results with new language
                currentAniListPage = 1
                viewModel.resetPage()
                loadResults()
            }
            
            builder.setNegativeButton("Cancel", null)
            builder.show()
        }
    }

    /**
     * Helper to update TMDB genre adapter based on selected format (Movie/TV)
     */
    private fun updateTmdbGenreAdapter(
        dialogBinding: com.lagradost.cloudstream3.databinding.BottomTmdbFilterBinding,
        format: String,
        selectedGenres: MutableSet<String>,
        excludedGenres: MutableSet<String>,
        dialogYear: String = "All",
        dialogCountry: String = "All",
        dialogProvider: String = "All",
        dialogTrending: String = "Off",
        dialogIncludeAdult: Boolean = false,
        dialogSort: String = "Popularity (High to Low)",
        dialogMinVotes: Int = 0
    ) {
        val genreList = TmdbFilterUtils.getGenresForFormat(format).map { it.second }
        
        var genreAdapter: AniListFilterUtils.AniListCheckboxAdapter? = null
        genreAdapter = AniListFilterUtils.AniListCheckboxAdapter(
            genreList,
            selectedGenres,
            excludedGenres,
            { item, state ->
                android.util.Log.d("TMDB_GENRE_DEBUG", "Genre callback: item=$item, state=$state")
                android.util.Log.d("TMDB_GENRE_DEBUG", "Before: dialogGenres=$selectedGenres, dialogExcludedGenres=$excludedGenres")
                when (state) {
                    0 -> { // unchecked
                        selectedGenres.remove(item)
                        excludedGenres.remove(item)
                    }
                    1 -> { // include
                        selectedGenres.add(item)
                        excludedGenres.remove(item)
                    }
                    2 -> { // exclude
                        selectedGenres.remove(item)
                        excludedGenres.add(item)
                    }
                }
                val totalCount = selectedGenres.size + excludedGenres.size
                dialogBinding.tmdbGenresCount.text = if (totalCount > 0) totalCount.toString() else "0"
                android.util.Log.d("TMDB_GENRE_DEBUG", "After: dialogGenres=$selectedGenres, dialogExcludedGenres=$excludedGenres, total=$totalCount")
                // Update only the single item that was clicked to avoid animating all checkboxes
                genreAdapter?.updateSingleItem(item, state)
                
                // Update TMDB Load Defaults button visibility
                updateTmdbLoadDefaultsButtonVisibility(
                    dialogBinding, format, selectedGenres, excludedGenres, dialogYear,
                    dialogCountry, dialogProvider, dialogTrending, dialogIncludeAdult, dialogSort,
                    dialogMinVotes
                )
            },
            radioMode = false
        )
        dialogBinding.tmdbGenresRecycler.adapter = genreAdapter
        dialogBinding.tmdbGenresRecycler.layoutManager = LinearLayoutManager(dialogBinding.root.context)
        dialogBinding.tmdbGenresRecycler.itemAnimator = null
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

    private fun loadTmdbDefaultFilters() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        
        val defaultFormat = prefs.getString("tmdb_default_format", "Movie") ?: "Movie"
        val defaultGenres = prefs.getStringSet("tmdb_default_genres", emptySet())?.toMutableSet() ?: mutableSetOf()
        val defaultExcludedGenres = prefs.getStringSet("tmdb_default_excluded_genres", emptySet())?.toMutableSet() ?: mutableSetOf()
        val defaultYear = prefs.getString("tmdb_default_year", "All") ?: "All"
        val defaultCountry = prefs.getString("tmdb_default_country", "All") ?: "All"
        val defaultProvider = prefs.getString("tmdb_default_provider", "All") ?: "All"
        val defaultTrending = prefs.getString("tmdb_default_trending", "Off") ?: "Off"
        val defaultIncludeAdult = prefs.getBoolean("tmdb_default_include_adult", false)
        val defaultSort = prefs.getString("tmdb_default_sort", "Popularity (High to Low)") ?: "Popularity (High to Low)"
        val defaultMinVotes = prefs.getInt("tmdb_default_min_votes", 0)
        val defaultKeywords = prefs.getString("tmdb_default_keywords", "") ?: ""

        selectedTmdbFormat = if (defaultFormat == "Movie") TmdbFormat.MOVIE else TmdbFormat.TV
        selectedTmdbGenres.clear()
        selectedTmdbGenres.addAll(defaultGenres)
        excludedTmdbGenres.clear()
        excludedTmdbGenres.addAll(defaultExcludedGenres)
        selectedTmdbYear = defaultYear
        selectedTmdbCountry = defaultCountry
        selectedTmdbProvider = defaultProvider
        selectedTmdbTrending = defaultTrending
        selectedTmdbIncludeAdult = defaultIncludeAdult
        selectedSort = defaultSort
        selectedTmdbMinVotes = defaultMinVotes
        selectedTmdbKeywords = defaultKeywords

        android.util.Log.d("BrowseFragment", "loadTmdbDefaultFilters: loaded format=$selectedTmdbFormat, genres=$selectedTmdbGenres, excludedGenres=$excludedTmdbGenres, year=$selectedTmdbYear, country=$selectedTmdbCountry, provider=$selectedTmdbProvider, trending=$selectedTmdbTrending, includeAdult=$selectedTmdbIncludeAdult, sort=$selectedSort, minVotes=$selectedTmdbMinVotes")
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
        
        // Load TMDB defaults if TMDB is the selected provider
        if (selectedProvider == FilterProvider.TMDB) {
            android.util.Log.d("BrowseFragment", "loadDefaultFilters: TMDB is selected, loading TMDB defaults")
            loadTmdbDefaultFilters()
        }
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

    // TMDB-specific helper functions for load defaults visibility
    private fun hasTmdbCustomDefaults(): Boolean {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        val defaultFormat = prefs.getString("tmdb_default_format", "Movie")
        val defaultGenres = prefs.getStringSet("tmdb_default_genres", null)
        val defaultExcludedGenres = prefs.getStringSet("tmdb_default_excluded_genres", null)
        val defaultYear = prefs.getString("tmdb_default_year", "All")
        val defaultCountry = prefs.getString("tmdb_default_country", "All")
        val defaultProvider = prefs.getString("tmdb_default_provider", "All")
        val defaultTrending = prefs.getString("tmdb_default_trending", "Off")
        val defaultIncludeAdult = prefs.getBoolean("tmdb_default_include_adult", false)
        val defaultSort = prefs.getString("tmdb_default_sort", "Popularity (High to Low)")
        val defaultMinVotes = prefs.getInt("tmdb_default_min_votes", 0)

        // Check if any default has been set to non-default values
        return (defaultFormat != "Movie") ||
               (defaultGenres != null && defaultGenres.isNotEmpty()) ||
               (defaultExcludedGenres != null && defaultExcludedGenres.isNotEmpty()) ||
               (defaultYear != "All") ||
               (defaultCountry != "All") ||
               (defaultProvider != "All") ||
               (defaultTrending != "Off") ||
               defaultIncludeAdult ||
               (defaultSort != "Popularity (High to Low)") ||
               (defaultMinVotes != 0)
    }

    private fun currentTmdbSettingsDifferFromDefaults(
        currentFormat: String,
        currentGenres: Set<String>,
        currentExcludedGenres: Set<String>,
        currentYear: String,
        currentCountry: String,
        currentProvider: String,
        currentTrending: String,
        currentIncludeAdult: Boolean,
        currentSort: String,
        currentMinVotes: Int
    ): Boolean {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        val defaultFormat = prefs.getString("tmdb_default_format", "Movie")
        val defaultGenres = prefs.getStringSet("tmdb_default_genres", emptySet())
        val defaultExcludedGenres = prefs.getStringSet("tmdb_default_excluded_genres", emptySet())
        val defaultYear = prefs.getString("tmdb_default_year", "All")
        val defaultCountry = prefs.getString("tmdb_default_country", "All")
        val defaultProvider = prefs.getString("tmdb_default_provider", "All")
        val defaultTrending = prefs.getString("tmdb_default_trending", "Off")
        val defaultIncludeAdult = prefs.getBoolean("tmdb_default_include_adult", false)
        val defaultSort = prefs.getString("tmdb_default_sort", "Popularity (High to Low)")
        val defaultMinVotes = prefs.getInt("tmdb_default_min_votes", 0)

        android.util.Log.d("BrowseFragment", "TMDB_LOAD_DEFAULTS_DEBUG: Comparing current TMDB to defaults")
        android.util.Log.d("BrowseFragment", "TMDB_LOAD_DEFAULTS_DEBUG: currentFormat=$currentFormat, defaultFormat=$defaultFormat")
        android.util.Log.d("BrowseFragment", "TMDB_LOAD_DEFAULTS_DEBUG: currentGenres=$currentGenres, defaultGenres=$defaultGenres")
        android.util.Log.d("BrowseFragment", "TMDB_LOAD_DEFAULTS_DEBUG: currentYear=$currentYear, defaultYear=$defaultYear")
        android.util.Log.d("BrowseFragment", "TMDB_LOAD_DEFAULTS_DEBUG: currentSort=$currentSort, defaultSort=$defaultSort")

        val differs = currentFormat != defaultFormat ||
                     currentGenres != defaultGenres ||
                     currentExcludedGenres != defaultExcludedGenres ||
                     currentYear != defaultYear ||
                     currentCountry != defaultCountry ||
                     currentProvider != defaultProvider ||
                     currentTrending != defaultTrending ||
                     currentIncludeAdult != defaultIncludeAdult ||
                     currentSort != defaultSort ||
                     currentMinVotes != defaultMinVotes

        android.util.Log.d("BrowseFragment", "TMDB_LOAD_DEFAULTS_DEBUG: TMDB settings differ from defaults: $differs")
        return differs
    }

    private fun updateTmdbLoadDefaultsButtonVisibility(
        dialogBinding: com.lagradost.cloudstream3.databinding.BottomTmdbFilterBinding,
        currentFormat: String,
        currentGenres: Set<String>,
        currentExcludedGenres: Set<String>,
        currentYear: String,
        currentCountry: String,
        currentProvider: String,
        currentTrending: String,
        currentIncludeAdult: Boolean,
        currentSort: String,
        currentMinVotes: Int
    ) {
        val hasDefaults = hasTmdbCustomDefaults()
        val differs = currentTmdbSettingsDifferFromDefaults(
            currentFormat, currentGenres, currentExcludedGenres, currentYear, currentCountry,
            currentProvider, currentTrending, currentIncludeAdult, currentSort, currentMinVotes
        )
        val shouldShow = hasDefaults && differs

        android.util.Log.d("BrowseFragment", "TMDB_LOAD_DEFAULTS_DEBUG: updateTmdbLoadDefaultsButtonVisibility: hasDefaults=$hasDefaults, differs=$differs, shouldShow=$shouldShow")
        dialogBinding.loadDefaultButton.visibility = if (shouldShow) View.VISIBLE else View.GONE
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
    
    // SORT_SYNC_FIX: Sync sort value between different providers
    private fun syncSortValueForProvider(currentSort: String, targetProvider: FilterProvider): String {
        android.util.Log.d("SORT_SYNC_FIX", "Syncing sort value: currentSort=$currentSort, targetProvider=$targetProvider")
        
        return when (targetProvider) {
            FilterProvider.ANILIST -> {
                // Map TMDB sort to AniList sort
                when (currentSort) {
                    "Popularity (High to Low)" -> "Popularity"
                    "Rating (High to Low)" -> "Average Score"
                    "Release Date (Newest)" -> "Release Date"
                    "Title (A-Z)" -> "Title"
                    else -> "Popularity" // Default fallback
                }
            }
            FilterProvider.TMDB -> {
                // Map AniList sort to TMDB sort
                when (currentSort) {
                    "Popularity" -> "Popularity (High to Low)"
                    "Average Score" -> "Rating (High to Low)"
                    "Release Date" -> "Release Date (Newest)"
                    "Title" -> "Title (A-Z)"
                    "Trending" -> "Popularity (High to Low)" // TMDB doesn't have trending
                    "Favorites" -> "Popularity (High to Low)" // TMDB doesn't have favorites
                    "Date Added" -> "Popularity (High to Low)" // TMDB doesn't have date added
                    else -> "Popularity (High to Low)" // Default fallback
                }
            }
        }
    }
    
    // TAB_STATE_FIX: Save provider state to SharedPreferences
    private fun saveProviderState() {
        android.util.Log.d("TAB_STATE_FIX", "Saving provider state: selectedProvider=$selectedProvider")
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        
        // SCROLL_POSITION_FIX: Save scroll position
        val scrollPosition = binding?.browseResults?.layoutManager?.let { layoutManager ->
            if (layoutManager is androidx.recyclerview.widget.LinearLayoutManager) {
                layoutManager.findFirstVisibleItemPosition()
            } else 0
        } ?: 0
        
        prefs.edit().apply {
            putString("browse_selected_provider", selectedProvider.name)
            putString("browse_selected_sort", selectedSort)
            putInt("browse_scroll_position", scrollPosition)
            apply()
        }
        android.util.Log.d("TAB_STATE_FIX", "Provider state saved successfully, scroll position: $scrollPosition")
    }
    
    // TAB_STATE_FIX: Restore provider state from SharedPreferences
    private fun restoreProviderState() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        val savedProvider = prefs.getString("browse_selected_provider", null)
        val savedSort = prefs.getString("browse_selected_sort", null)
        val savedScrollPosition = prefs.getInt("browse_scroll_position", 0)
        
        android.util.Log.d("TAB_STATE_FIX", "Restoring provider state: savedProvider=$savedProvider, savedSort=$savedSort, savedScrollPosition=$savedScrollPosition")
        
        if (savedProvider != null) {
            try {
                val restoredProvider = FilterProvider.valueOf(savedProvider)
                if (restoredProvider != selectedProvider) {
                    android.util.Log.d("TAB_STATE_FIX", "Provider changed from $selectedProvider to $restoredProvider")
                    selectedProvider = restoredProvider
                    
                    // Load defaults when switching to TMDB
                    if (selectedProvider == FilterProvider.TMDB) {
                        android.util.Log.d("TAB_STATE_FIX", "Switched to TMDB, loading TMDB defaults")
                        loadTmdbDefaultFilters()
                    }
                    
                    // Update search hint
                    val newSearchHint = when (selectedProvider) {
                        FilterProvider.ANILIST -> "Search in AniList"
                        FilterProvider.TMDB -> "Search in TMDB"
                    }
                    binding?.browseSearch?.queryHint = newSearchHint
                    
                    // Update metadata language button state
                    val isAniListSelected = selectedProvider == FilterProvider.ANILIST
                    binding?.apply {
                        metadataLanguageDropdown.alpha = if (isAniListSelected) 0.5f else 1.0f
                        metadataLanguageDropdown.isEnabled = !isAniListSelected
                        metadataLanguageText.alpha = if (isAniListSelected) 0.5f else 1.0f
                        metadataLanguageText.isEnabled = !isAniListSelected
                    }
                    
                    // Update filter states
                    val actualSearchMode = !searchQuery.isNullOrBlank()
                    updateFilterStatesForSearch(actualSearchMode)
                    updateUI()
                }
            } catch (e: Exception) {
                android.util.Log.e("TAB_STATE_FIX", "Error restoring provider: $savedProvider", e)
            }
        }
        
        if (savedSort != null && savedSort != selectedSort) {
            android.util.Log.d("TAB_STATE_FIX", "Sort changed from $selectedSort to $savedSort")
            selectedSort = syncSortValueForProvider(savedSort, selectedProvider)
            updateUI()
        }
        
        // SCROLL_POSITION_FIX: Restore scroll position after data is loaded
        if (savedScrollPosition > 0) {
            binding?.browseResults?.post {
                binding?.browseResults?.scrollToPosition(savedScrollPosition)
                android.util.Log.d("TAB_STATE_FIX", "Restored scroll position to: $savedScrollPosition")
            }
        }
        
        android.util.Log.d("TAB_STATE_FIX", "Provider state restoration completed")
    }

    private fun loadAniListResults() {
        android.util.Log.d("BrowseFragment", "========== loadAniListResults called ==========")
        android.util.Log.d("BrowseFragment", "loadAniListResults: isLoadingMoreResults=$isLoadingMoreResults, currentAniListPage=$currentAniListPage, hasMoreResults=$hasMoreResults")
        
        // Prevent empty or whitespace-only search queries from hanging
        if (!searchQuery.isNullOrBlank()) {
            android.util.Log.d("BrowseFragment", "loadAniListResults: Non-empty searchQuery detected: '$searchQuery'")
        } else {
            android.util.Log.d("BrowseFragment", "loadAniListResults: Empty or null searchQuery, proceeding with browse/discover mode")
        }

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

                val browseItems = validMediaItems.mapNotNull { it.toBrowseMediaItem() }
                android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: Converted ${validMediaItems.size} valid media items to ${browseItems.size} BrowseMediaItem items")
                android.util.Log.d("BrowseFragment", "loadAniListResults: Converted to ${browseItems.size} BrowseMediaItem items")

                main {
                    binding?.browseLoadingBar?.visibility = View.GONE
                    android.util.Log.d("BrowseFragment", "loadAniListResults: Set browseLoadingBar visibility to GONE")

                    if (currentAniListPage == 1) {
                        android.util.Log.d("BrowseFragment", "loadAniListResults: Calling viewModel.updateResults with ${browseItems.size} items")
                        viewModel.updateResults(browseItems, hasNextPage)
                    } else {
                        android.util.Log.d("BrowseFragment", "loadAniListResults: Calling viewModel.appendResults with ${browseItems.size} items")
                        viewModel.appendResults(browseItems, hasNextPage)
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

    private fun loadTmdbResults() {
        android.util.Log.d("BrowseFragment", "========== loadTmdbResults called ==========")
        android.util.Log.d("BrowseFragment", "loadTmdbResults: isLoadingMoreResults=$isLoadingMoreResults, currentAniListPage=$currentAniListPage, hasMoreResults=$hasMoreResults")
        
        // Check if we should use Search endpoint (user typed in search bar) or Discover/Trending (browse mode)
        val isSearchMode = !searchQuery.isNullOrBlank()
        android.util.Log.d("BrowseFragment", "loadTmdbResults: isSearchMode=$isSearchMode, searchQuery='$searchQuery'")

        viewModel.setLoading(true)
        android.util.Log.d("BrowseFragment", "loadTmdbResults: Set viewModel loading to true")

        // Check if TMDB is enabled
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val apiKey = prefs.getString(TmdbApi.API_KEY_PREF, null)
        
        if (apiKey.isNullOrBlank()) {
            android.util.Log.e("BrowseFragment", "loadTmdbResults: TMDB API key not set")
            viewModel.setError(BrowseError.MISSING_TMDB_KEY)
            viewModel.setLoading(false)
            main {
                com.lagradost.cloudstream3.CommonActivity.showToast("Please set TMDB API key in Settings")
            }
            return
        }

        // Save current filter state to ViewModel only on first page load
        if (currentAniListPage == 1) {
            android.util.Log.d("STATE_SYNC_FIX", "loadTmdbResults: Syncing filter state to ViewModel on first page load")
            val currentFilters = BrowseFilterState(
                provider = FilterProvider.TMDB,
                genres = selectedGenres,
                tags = selectedTags,
                excludedGenres = excludedGenres,
                excludedTags = excludedTags,
                year = selectedYear,
                season = selectedSeason,
                format = selectedFormat,
                sort = selectedSort,
                tmdbFormat = selectedTmdbFormat,
                tmdbGenres = selectedTmdbGenres,
                tmdbExcludedGenres = excludedTmdbGenres,
                tmdbYear = selectedTmdbYear,
                tmdbCountry = selectedTmdbCountry,
                tmdbProvider = selectedTmdbProvider,
                tmdbTrending = selectedTmdbTrending,
                    tmdbIncludeAdult = selectedTmdbIncludeAdult,
                    tmdbKeywords = selectedTmdbKeywords,
                    tmdbMinVotes = selectedTmdbMinVotes
            )
            android.util.Log.d("STATE_SYNC_FIX", "loadTmdbResults: Calling viewModel.updateFilters with currentFilters=$currentFilters")
            viewModel.updateFilters(currentFilters)
        } else {
            android.util.Log.d("BrowseFragment", "loadTmdbResults: Skipping filter update (loading more results, page=$currentAniListPage)")
        }

        ioSafe {
            try {
                android.util.Log.d("API_ERROR_HANDLING", "API_ERROR_HANDLING: Starting TMDB API call")
                
                main {
                    android.util.Log.d("BrowseFragment", "loadTmdbResults: Using search bar spinner only")
                }

                val sort = TmdbFilterUtils.convertSortToApi(selectedSort)
                val isMovie = selectedTmdbFormat == TmdbFormat.MOVIE
                val isTrending = selectedTmdbTrending != "Off"
                
                android.util.Log.d("BrowseFragment", "loadTmdbResults: format=$selectedTmdbFormat, isMovie=$isMovie, isTrending=$isTrending")
                android.util.Log.d("BrowseFragment", "loadTmdbResults: genres=$selectedTmdbGenres, excludedGenres=$excludedTmdbGenres")
                android.util.Log.d("BrowseFragment", "loadTmdbResults: year=$selectedTmdbYear, country=$selectedTmdbCountry")
                android.util.Log.d("BrowseFragment", "loadTmdbResults: provider=$selectedTmdbProvider, trending=$selectedTmdbTrending")
                android.util.Log.d("BrowseFragment", "loadTmdbResults: adult=$selectedTmdbIncludeAdult, sort=$sort")

                // Convert genre names to IDs for TMDB API
                val formatName = if (isMovie) "Movie" else "TV Show"
                val genreIds = selectedTmdbGenres.mapNotNull { 
                    TmdbFilterUtils.getGenreIdByName(formatName, it) 
                }
                val excludedGenreIds = excludedTmdbGenres.mapNotNull { 
                    TmdbFilterUtils.getGenreIdByName(formatName, it) 
                }
                
                // Convert country name to code
                val countryCode = TmdbFilterUtils.getCountryCode(selectedTmdbCountry)
                
                // Convert provider name to ID
                val providerId = TmdbFilterUtils.getProviderId(selectedTmdbProvider)
                
                // Convert trending display to time window
                val timeWindow = if (isTrending) TmdbFilterUtils.getTrendingTimeWindow(selectedTmdbTrending) else "week"

                val results: List<BrowseMediaItem>? = if (isSearchMode) {
                    // CASE A: Text Search Mode - Use Search Multi endpoint
                    // Note: TMDB Search endpoint ignores all filters, only uses the query text
                    android.util.Log.d("BrowseFragment", "loadTmdbResults: Using Search Multi endpoint for query: '$searchQuery'")
                    TmdbApi.searchMulti(
                        context = requireContext(),
                        apiKey = apiKey,
                        query = searchQuery!!,
                        page = currentAniListPage,
                        includeAdult = selectedTmdbIncludeAdult
                    )
                } else if (isTrending) {
                    // CASE B: Trending Mode - Use Trending endpoint
                    val mediaType = when (selectedTmdbFormat) {
                        TmdbFormat.MOVIE -> "movie"
                        TmdbFormat.TV -> "tv"
                    }
                    android.util.Log.d("BrowseFragment", "loadTmdbResults: Using Trending endpoint for $mediaType/$timeWindow")
                    TmdbApi.getTrending(
                        context = requireContext(),
                        apiKey = apiKey,
                        mediaType = mediaType,
                        timeWindow = timeWindow ?: "week",
                        page = currentAniListPage
                    )
                } else if (isMovie) {
                    // CASE C: Filtered Browse Mode - Use Discover Movies endpoint
                    android.util.Log.d("BrowseFragment", "loadTmdbResults: Using Discover Movies endpoint with filters")
                    TmdbApi.discoverMovies(
                        context = requireContext(),
                        apiKey = apiKey,
                        genres = genreIds,
                        excludedGenres = excludedGenreIds,
                        keywords = selectedTmdbKeywords,
                        minVotes = selectedTmdbMinVotes,
                        year = selectedTmdbYear.takeIf { it != "All" },
                        country = countryCode,
                        provider = providerId,
                        sort = sort,
                        includeAdult = selectedTmdbIncludeAdult,
                        page = currentAniListPage
                    )
                } else {
                    // CASE C: Filtered Browse Mode - Use Discover TV endpoint
                    android.util.Log.d("BrowseFragment", "loadTmdbResults: Using Discover TV endpoint with filters")
                    TmdbApi.discoverTv(
                        context = requireContext(),
                        apiKey = apiKey,
                        genres = genreIds,
                        excludedGenres = excludedGenreIds,
                        keywords = selectedTmdbKeywords,
                        minVotes = selectedTmdbMinVotes,
                        year = selectedTmdbYear.takeIf { it != "All" },
                        country = countryCode,
                        provider = providerId,
                        sort = sort,
                        includeAdult = selectedTmdbIncludeAdult,
                        page = currentAniListPage
                    )
                }

                android.util.Log.d("API_ERROR_HANDLING", "API_ERROR_HANDLING: TMDB API call completed, results size = ${results?.size}")
                
                if (results == null) {
                    android.util.Log.e("API_ERROR_HANDLING", "API_ERROR_HANDLING: TMDB API response is null")
                    throw Exception("TMDB API response is null - possible network error or API failure")
                }

                val hasNextPage = results.size >= 20 // TMDB default page size is 20
                
                android.util.Log.d("BrowseFragment", "loadTmdbResults: Received ${results.size} media items, hasNextPage=$hasNextPage")

                main {
                    binding?.browseLoadingBar?.visibility = View.GONE
                    android.util.Log.d("BrowseFragment", "loadTmdbResults: Set browseLoadingBar visibility to GONE")

                    if (currentAniListPage == 1) {
                        android.util.Log.d("BrowseFragment", "loadTmdbResults: Calling viewModel.updateResults with ${results.size} items")
                        viewModel.updateResults(results, hasNextPage)
                    } else {
                        android.util.Log.d("BrowseFragment", "loadTmdbResults: Calling viewModel.appendResults with ${results.size} items")
                        viewModel.appendResults(results, hasNextPage)
                    }

                    isLoadingMoreResults = false
                    hasMoreResults = hasNextPage
                    viewModel.setLoading(false)
                    android.util.Log.d("BrowseFragment", "loadTmdbResults: Set isLoadingMoreResults to false, hasMoreResults to $hasNextPage, viewModel loading to false")
                    android.util.Log.d("API_ERROR_HANDLING", "API_ERROR_HANDLING: Successfully updated UI with TMDB results")
                }
            } catch (e: Exception) {
                android.util.Log.e("API_ERROR_HANDLING", "API_ERROR_HANDLING: ERROR in loadTmdbResults", e)
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
                    com.lagradost.cloudstream3.CommonActivity.showToast("Failed to load TMDB results: ${e.message}")
                }
            }
        }
        android.util.Log.d("BrowseFragment", "========== loadTmdbResults completed ==========")
    }

    /**
     * Unified load method that dispatches to the appropriate provider
     */
    private fun loadResults() {
        android.util.Log.d("BrowseFragment", "========== loadResults called ==========")
        android.util.Log.d("BrowseFragment", "loadResults: selectedProvider=$selectedProvider")
        
        // Check TMDB enabled status before loading
        if (selectedProvider == FilterProvider.TMDB) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val apiKey = prefs.getString(TmdbApi.API_KEY_PREF, null)
            viewModel.setTmdbEnabled(!apiKey.isNullOrBlank())
            
            if (apiKey.isNullOrBlank()) {
                viewModel.setError(BrowseError.MISSING_TMDB_KEY)
                // Show error message
                main {
                    com.lagradost.cloudstream3.CommonActivity.showToast("TMDB API key required. Please set it in Settings.")
                }
                return
            }
        }
        
        when (selectedProvider) {
            FilterProvider.ANILIST -> loadAniListResults()
            FilterProvider.TMDB -> loadTmdbResults()
        }
    }

    private fun loadMoreResults() {
        android.util.Log.d("BrowseFragment", "========== loadMoreResults called ==========")
        android.util.Log.d("BrowseFragment", "loadMoreResults: isLoadingMoreResults=$isLoadingMoreResults, hasMoreResults=$hasMoreResults, selectedProvider=$selectedProvider")
        if (isLoadingMoreResults || !hasMoreResults) {
            android.util.Log.d("BrowseFragment", "loadMoreResults: Returning early - isLoadingMoreResults=$isLoadingMoreResults, hasMoreResults=$hasMoreResults")
            return
        }

        android.util.Log.d("BrowseFragment", "loadMoreResults: Setting isLoadingMoreResults to true and incrementing page")
        isLoadingMoreResults = true
        viewModel.incrementPage()
        currentAniListPage = viewModel.uiState.value?.currentPage ?: 1
        android.util.Log.d("BrowseFragment", "loadMoreResults: Page incremented to $currentAniListPage")
        
        // Call appropriate loader based on provider
        when (selectedProvider) {
            FilterProvider.ANILIST -> loadAniListResults()
            FilterProvider.TMDB -> loadTmdbResults()
        }
        android.util.Log.d("BrowseFragment", "========== loadMoreResults completed ==========")
    }

    private fun AniListApi.MediaByGenreItem.toBrowseMediaItem(): BrowseMediaItem? {
        android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: Converting MediaByGenreItem to BrowseMediaItem")
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
        
        return BrowseMediaItem(
            id = "anilist_${this.id}",
            title = name,
            posterUrl = posterUrl,
            type = BrowseMediaType.ANIME, // Default to ANIME since this is from AniList
            provider = FilterProvider.ANILIST,
            sourceData = this
        )
    }

    /**
     * Updates filter dialog accordion visibility based on selected provider.
     * AniList shows: Genres, Tags, Year, Season, Format, Sort
     * TMDB shows: Format, Genres, Year, Season (if year selected), Language, Provider, Rating, Runtime, Sort, NSFW
     */
    private fun updateFilterDialogVisibility(
        dialogBinding: com.lagradost.cloudstream3.databinding.BottomAnilistGenreTagSelectorBinding,
        provider: FilterProvider
    ) {
        android.util.Log.d("BrowseFragment", "updateFilterDialogVisibility: provider=$provider")
        
        when (provider) {
            FilterProvider.ANILIST -> {
                // Show AniList-specific accordions
                dialogBinding.tagsHeader.visibility = View.VISIBLE
                dialogBinding.tagsRecycler.visibility = if (dialogBinding.tagsRecycler.visibility == View.VISIBLE) View.VISIBLE else View.GONE
                
                // TMDB-specific accordions are hidden
                // (We'll add these views to the layout separately for TMDB)
            }
            FilterProvider.TMDB -> {
                // Hide AniList-specific accordions
                dialogBinding.tagsHeader.visibility = View.GONE
                dialogBinding.tagsRecycler.visibility = View.GONE
                
                // TMDB-specific accordions will be shown
                // (These are part of the separate TMDB filter layout)
            }
        }
        
        // Genres accordion is shown for both, but uses different genre lists
        // This is handled by updating the adapter when format changes
    }
}
