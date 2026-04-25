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

    private var resultsList = emptyList<SearchResponse>()
    private var currentAniListPage = 1
    private var hasMoreResults = false
    private var isLoadingMoreResults = false
    private var searchQuery: String? = null

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
            // Restore filter state from ViewModel
            resultsList = viewModel.uiState.value?.results ?: emptyList()
            currentAniListPage = viewModel.uiState.value?.currentPage ?: 1
            hasMoreResults = viewModel.uiState.value?.hasMore ?: false

            // Restore filter selections
            val filters = viewModel.uiState.value?.filters
            selectedGenres = filters?.genres?.toMutableSet() ?: mutableSetOf()
            selectedTags = filters?.tags?.toMutableSet() ?: mutableSetOf()
            selectedYear = filters?.year ?: "All"
            selectedSeason = filters?.season ?: "All"
            selectedFormat = filters?.format ?: "All"
            selectedSort = filters?.sort ?: "Popularity"

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

    private fun navigateToSearch(query: String) {
        android.util.Log.d("BrowseFragment", "========== navigateToSearch called ==========")
        android.util.Log.d("BrowseFragment", "navigateToSearch: query = $query")
        val activity = requireActivity()
        // Set the search query in MainActivity
        if (activity is com.lagradost.cloudstream3.MainActivity) {
            android.util.Log.d("BrowseFragment", "navigateToSearch: setting MainActivity.nextSearchQuery = $query")
            com.lagradost.cloudstream3.MainActivity.nextSearchQuery = query
            android.util.Log.d("BrowseFragment", "navigateToSearch: MainActivity.nextSearchQuery is now ${com.lagradost.cloudstream3.MainActivity.nextSearchQuery}")
        }
        // Navigate to Search tab using bottom navigation only
        android.util.Log.d("BrowseFragment", "navigateToSearch: selecting Search tab in bottom navigation")
        activity.findViewById<BottomNavigationView>(R.id.nav_view)?.selectedItemId = R.id.navigation_search
        activity.findViewById<NavigationRailView>(R.id.nav_rail_view)?.selectedItemId = R.id.navigation_search
        android.util.Log.d("BrowseFragment", "========== navigateToSearch completed ==========")
    }

    private fun updateResultsPadding() {
        binding?.apply {
            // The topbar height already includes chips when they're visible
            // Just use the topbar height as the padding
            val topBarHeight = topBarContainer.height
            val targetPadding = topBarHeight
            val currentPadding = browseResults.paddingTop
            
            android.util.Log.d("BrowseFragment", "updateResultsPadding: topBarHeight=$topBarHeight, currentPadding=$currentPadding, targetPadding=$targetPadding")
            
            // Set padding immediately when topbar height changes
            if (currentPadding != targetPadding) {
                browseResults.setPadding(
                    browseResults.paddingLeft,
                    targetPadding,
                    browseResults.paddingRight,
                    browseResults.paddingBottom
                )
                // Scroll to top to prevent hollow space
                browseResults.scrollToPosition(0)
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
                android.util.Log.d("BrowseFragment", "SearchAdapter callback: title=$title")
                navigateToSearch(title)
            }

            browseResults.setRecycledViewPool(SearchAdapter.sharedPool)
            browseResults.adapter = adapter
            android.util.Log.d("BrowseFragment", "setupUI: Set adapter to browseResults")

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
                                .setDuration(200)
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
                                .setDuration(200)
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
            // Wait for layout to complete before updating padding
            chipGroup.post { updateResultsPadding() }
        } else {
            chipGroup.visibility = View.GONE
            // Update padding after chip visibility changes
            updateResultsPadding()
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
            // Wait for layout to complete before updating padding
            chipGroup.post { updateResultsPadding() }
        } else {
            chipGroup.visibility = View.GONE
            // Update padding after chip visibility changes
            updateResultsPadding()
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
                // Update adapter's internal sets
                genresAdapter?.updateSelectedSet(dialogGenres)
                genresAdapter?.updateExcludedSet(dialogExcludedGenres)
            })
            dialogBinding.genresRecycler.adapter = genresAdapter
            dialogBinding.genresRecycler.layoutManager = LinearLayoutManager(ctx)

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
                // Update adapter's internal sets
                tagsAdapter?.updateSelectedSet(dialogTags)
                tagsAdapter?.updateExcludedSet(dialogExcludedTags)
            })
            dialogBinding.tagsRecycler.adapter = tagsAdapter
            dialogBinding.tagsRecycler.layoutManager = LinearLayoutManager(ctx)

            // Setup years adapter (radio mode)
            val selectedYearsSet = if (dialogYear != "All") setOf(dialogYear) else setOf("All")
            var yearsAdapter: AniListFilterUtils.AniListCheckboxAdapter? = null
            yearsAdapter = AniListFilterUtils.AniListCheckboxAdapter(years, selectedYearsSet, emptySet(), { item, state ->
                if (state == 1) {
                    dialogYear = item
                } else {
                    dialogYear = "All"
                }
                val newSelectedSet = setOf(dialogYear).filterNotNull().toSet()
                dialogBinding.yearRecycler.post {
                    yearsAdapter?.updateSelectedSet(newSelectedSet)
                }
                dialogBinding.yearCount.text = dialogYear
            }, radioMode = true)
            dialogBinding.yearRecycler.adapter = yearsAdapter
            dialogBinding.yearRecycler.layoutManager = LinearLayoutManager(ctx)

            // Setup seasons adapter (radio mode)
            val selectedSeasonsSet = if (dialogSeason != "All") setOf(dialogSeason) else setOf("All")
            var seasonsAdapter: AniListFilterUtils.AniListCheckboxAdapter? = null
            seasonsAdapter = AniListFilterUtils.AniListCheckboxAdapter(seasons, selectedSeasonsSet, emptySet(), { item, state ->
                if (state == 1) {
                    dialogSeason = item
                } else {
                    dialogSeason = "All"
                }
                val newSelectedSet = setOf(dialogSeason).filterNotNull().toSet()
                dialogBinding.seasonRecycler.post {
                    seasonsAdapter?.updateSelectedSet(newSelectedSet)
                }
                dialogBinding.seasonCount.text = dialogSeason
            }, radioMode = true)
            dialogBinding.seasonRecycler.adapter = seasonsAdapter
            dialogBinding.seasonRecycler.layoutManager = LinearLayoutManager(ctx)

            // Setup formats adapter (radio mode)
            val selectedFormatsSet = if (dialogFormat != "All") setOf(dialogFormat) else setOf("All")
            var formatsAdapter: AniListFilterUtils.AniListCheckboxAdapter? = null
            formatsAdapter = AniListFilterUtils.AniListCheckboxAdapter(formats, selectedFormatsSet, emptySet(), { item, state ->
                if (state == 1) {
                    dialogFormat = item
                } else {
                    dialogFormat = "All"
                }
                val newSelectedSet = setOf(dialogFormat).filterNotNull().toSet()
                dialogBinding.formatRecycler.post {
                    formatsAdapter?.updateSelectedSet(newSelectedSet)
                }
                dialogBinding.formatCount.text = dialogFormat
            }, radioMode = true)
            dialogBinding.formatRecycler.adapter = formatsAdapter
            dialogBinding.formatRecycler.layoutManager = LinearLayoutManager(ctx)

            // Setup sort adapter (radio mode)
            val selectedSortSet = if (dialogSort != "All") setOf(dialogSort) else setOf("All")
            var sortAdapter: AniListFilterUtils.AniListCheckboxAdapter? = null
            sortAdapter = AniListFilterUtils.AniListCheckboxAdapter(sortOptions, selectedSortSet, emptySet(), { item, state ->
                if (state == 1) {
                    dialogSort = item
                } else {
                    dialogSort = "All"
                }
                val newSelectedSet = setOf(dialogSort).filterNotNull().toSet()
                dialogBinding.sortRecycler.post {
                    sortAdapter?.updateSelectedSet(newSelectedSet)
                }
                dialogBinding.sortCount.text = dialogSort
            }, radioMode = true)
            dialogBinding.sortRecycler.adapter = sortAdapter
            dialogBinding.sortRecycler.layoutManager = LinearLayoutManager(ctx)

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

            // Set Default button
            dialogBinding.setDefaultButton.setOnClickListener {
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
                Toast.makeText(requireContext(), "Default settings saved", Toast.LENGTH_SHORT).show()
            }

            // Reset Default button - reset to app defaults (no filters)
            dialogBinding.resetDefaultButton.setOnClickListener {
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
                dialogBinding.genresCount.text = "0 / 0"
                dialogBinding.tagsCount.text = "0 / 0"
                dialogBinding.yearCount.text = "All"
                dialogBinding.seasonCount.text = "All"
                dialogBinding.formatCount.text = "All"
                dialogBinding.sortCount.text = "Popularity"

                Toast.makeText(requireContext(), "Reset to defaults", Toast.LENGTH_SHORT).show()
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
                dialogBinding.genresCount.text = "0 / 0"
                dialogBinding.tagsCount.text = "0 / 0"
            }

            // Apply button
            dialogBinding.applyButton.setOnClickListener {
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
            expandIcon.rotation = 0f
        } else {
            recyclerView.visibility = View.VISIBLE
            expandIcon.rotation = 90f
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

    private fun loadAniListResults() {
        android.util.Log.d("BrowseFragment", "========== loadAniListResults called ==========")
        android.util.Log.d("BrowseFragment", "loadAniListResults: isLoadingMoreResults=$isLoadingMoreResults, currentAniListPage=$currentAniListPage, hasMoreResults=$hasMoreResults")

        viewModel.setLoading(true)
        android.util.Log.d("BrowseFragment", "loadAniListResults: Set viewModel loading to true")

        // Save current filter state to ViewModel only on first page load
        if (currentAniListPage == 1) {
            val currentFilters = BrowseFilterState(
                genres = selectedGenres,
                tags = selectedTags,
                year = selectedYear,
                season = selectedSeason,
                format = selectedFormat,
                sort = selectedSort
            )
            viewModel.updateFilters(currentFilters)
            android.util.Log.d("BrowseFragment", "loadAniListResults: Updated filters: genres=$selectedGenres, year=$selectedYear, season=$selectedSeason, format=$selectedFormat, sort=$selectedSort")
        } else {
            android.util.Log.d("BrowseFragment", "loadAniListResults: Skipping filter update (loading more results, page=$currentAniListPage)")
        }

        ioSafe {
            main {
                // Only use search bar spinner now, no center loader
                android.util.Log.d("BrowseFragment", "loadAniListResults: Using search bar spinner only")
            }

            val seasonYear = if (selectedYear == "All") null else selectedYear?.toIntOrNull()
            val season = AniListFilterUtils.convertSeasonToApi(selectedSeason)
            val format = AniListFilterUtils.convertFormatToApi(selectedFormat)
            val sort = AniListFilterUtils.convertSortToApi(selectedSort)

            android.util.Log.d("BrowseFragment", "loadAniListResults: Calling aniListApi.getMediaByGenre with page=$currentAniListPage, seasonYear=$seasonYear, season=$season, format=$format, sort=$sort, searchQuery=$searchQuery, isAdult=$selectedNsfw, excludedGenres=$excludedGenres, excludedTags=$excludedTags")

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

            val mediaItems = response?.data?.page?.media ?: emptyList()
            val hasNextPage = response?.data?.page?.pageInfo?.hasNextPage ?: false

            android.util.Log.d("BrowseFragment", "loadAniListResults: Received ${mediaItems.size} media items, hasNextPage=$hasNextPage")

            val searchResponses = mediaItems.mapNotNull { it.toSearchResponse() }
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

    private fun AniListApi.MediaByGenreItem.toSearchResponse(): SearchResponse {
        @Suppress("DEPRECATION_ERROR")
        return AnimeSearchResponse(
            name = this.title?.romaji ?: this.title?.english ?: "",
            url = "https://anilist.co/anime/${this.id}",
            apiName = "AniList",
            type = TvType.Anime,
            id = this.id,
            posterUrl = this.coverImage?.large ?: this.coverImage?.medium
        )
    }
}
