package com.lagradost.cloudstream3.ui.browse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
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
    private var selectedTags = mutableSetOf<String>()
    private var selectedYear = "All"
    private var selectedSeason = "All"
    private var selectedFormat = "All"
    private var selectedSort = "Popularity"

    private var resultsList = emptyList<SearchResponse>()
    private var currentAniListPage = 1
    private var hasMoreResults = false
    private var isLoadingMoreResults = false
    private var searchQuery: String? = null

    // Top bar hide/show on scroll
    private var isTopBarVisible = true
    private var scrollAccumulator = 0
    private val scrollThreshold = 20 // Minimum scroll distance to trigger hide/show

    private val aniListApi = AccountManager.aniListApi

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padTop = false,
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
                    return false
                }
            })

            // Setup filter button click listener
            filterButton.setOnClickListener {
                android.util.Log.d("BrowseFragment", "filterButton clicked")
                showFilterDialog()
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

            // Dynamic top padding based on top bar height
            topBarContainer.post {
                val topBarHeight = topBarContainer.height
                android.util.Log.d("BrowseFragment", "setupUI: topBarHeight=$topBarHeight")
                browseResults.setPadding(
                    browseResults.paddingLeft,
                    topBarHeight + 16, // Add 16dp extra spacing to prevent shadowing
                    browseResults.paddingRight,
                    browseResults.paddingBottom
                )
            }

            // Update padding when top bar height changes (e.g., when tags are shown/hidden)
            topBarContainer.addOnLayoutChangeListener { _, _, top, _, _, bottom, oldBottom, _, _ ->
                if (bottom - top != oldBottom - oldBottom) {
                    val newHeight = bottom - top
                    android.util.Log.d("BrowseFragment", "setupUI: topBar height changed to $newHeight")
                    browseResults.setPadding(
                        browseResults.paddingLeft,
                        newHeight + 16, // Add 16dp extra spacing to prevent shadowing
                        browseResults.paddingRight,
                        browseResults.paddingBottom
                    )
                }
            }

            // Add scroll listener for auto-reload
            browseResults.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    val layoutManager = recyclerView.layoutManager as? androidx.recyclerview.widget.GridLayoutManager
                    
                    // Hide/show top bar based on scroll direction
                    scrollAccumulator += dy
                    
                    // Clamp accumulator to prevent unbounded growth
                    if (scrollAccumulator > scrollThreshold * 10) {
                        scrollAccumulator = scrollThreshold * 10
                    } else if (scrollAccumulator < -scrollThreshold * 10) {
                        scrollAccumulator = -scrollThreshold * 10
                    }
                    
                    if (scrollAccumulator > scrollThreshold && isTopBarVisible) {
                        // Scrolled down past threshold, hide top bar
                        binding?.topBarContainer?.let { topBar ->
                            topBar.animate()
                                .translationY(-topBar.height.toFloat())
                                .setDuration(200)
                                .start()
                        }
                        isTopBarVisible = false
                        scrollAccumulator = 0
                    } else if (scrollAccumulator < -scrollThreshold && !isTopBarVisible) {
                        // Scrolled up past threshold, show top bar
                        binding?.topBarContainer?.let { topBar ->
                            topBar.animate()
                                .translationY(0f)
                                .setDuration(200)
                                .start()
                        }
                        isTopBarVisible = true
                        scrollAccumulator = 0
                    }

                    val adapter = recyclerView.adapter as? SearchAdapter ?: return
                    val count = adapter.itemCount

                    if (layoutManager != null && hasMoreResults && !isLoadingMoreResults) {
                        val lastVisiblePosition = layoutManager.findLastVisibleItemPosition()
                        android.util.Log.d("BrowseFragment", "onScrolled: lastVisiblePosition=$lastVisiblePosition, itemCount=$count, threshold=${count - 7}")

                        if (lastVisiblePosition >= count - 7) {
                            android.util.Log.d("BrowseFragment", "onScrolled: Near end of list, calling loadMoreResults()")
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
            android.util.Log.d("BrowseFragment", "updateUI: browseSearchLoadingBar.alpha set to ${if (viewModel.uiState.value?.isLoading == true) 1f else 0f}")
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
        } else {
            chipGroup.visibility = View.GONE
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
        } else {
            chipGroup.visibility = View.GONE
        }
    }

    private fun showFilterDialog() {
        // Use shared constants from AniListFilterUtils
        val genres = AniListFilterUtils.GENRES
        val tags = AniListFilterUtils.TAGS
        val years = AniListFilterUtils.YEARS
        val seasons = AniListFilterUtils.SEASONS
        val formats = AniListFilterUtils.FORMATS
        val sortOptions = AniListFilterUtils.SORT_OPTIONS

        val dialogGenres = selectedGenres.toMutableSet()
        val dialogTags = selectedTags.toMutableSet()
        var dialogYear = selectedYear
        var dialogSeason = selectedSeason
        var dialogFormat = selectedFormat
        var dialogSort = selectedSort

        activity?.let { ctx ->
            val dialog = AlertDialog.Builder(ctx, R.style.AlertDialogCustom).create()
            val dialogBinding = com.lagradost.cloudstream3.databinding.BottomAnilistGenreTagSelectorBinding.inflate(
                dialog.layoutInflater,
                null,
                false
            )
            dialog.setView(dialogBinding.root)
            dialog.setTitle("Filter")

            // Setup genres adapter
            val genresAdapter = AniListFilterUtils.AniListCheckboxAdapter(genres, dialogGenres, { item, isChecked ->
                if (isChecked) {
                    dialogGenres.add(item)
                } else {
                    dialogGenres.remove(item)
                }
                dialogBinding.genresCount.text = "${dialogGenres.size}"
            })
            dialogBinding.genresRecycler.adapter = genresAdapter
            dialogBinding.genresRecycler.layoutManager = LinearLayoutManager(ctx)

            // Setup tags adapter
            val tagsAdapter = AniListFilterUtils.AniListCheckboxAdapter(tags, dialogTags, { item, isChecked ->
                if (isChecked) {
                    dialogTags.add(item)
                } else {
                    dialogTags.remove(item)
                }
                dialogBinding.tagsCount.text = "${dialogTags.size}"
            })
            dialogBinding.tagsRecycler.adapter = tagsAdapter
            dialogBinding.tagsRecycler.layoutManager = LinearLayoutManager(ctx)

            // Setup years adapter (radio mode)
            val selectedYearsSet = if (dialogYear != "All") setOf(dialogYear) else setOf("All")
            var yearsAdapter: AniListFilterUtils.AniListCheckboxAdapter? = null
            yearsAdapter = AniListFilterUtils.AniListCheckboxAdapter(years, selectedYearsSet, { item, isChecked ->
                if (isChecked) {
                    dialogYear = item
                } else {
                    dialogYear = "All"
                }
                val newSelectedSet = setOf(dialogYear).filterNotNull().toSet()
                dialogBinding.yearRecycler.post {
                    yearsAdapter?.updateSelectedSet(newSelectedSet)
                }
            }, radioMode = true)
            dialogBinding.yearRecycler.adapter = yearsAdapter
            dialogBinding.yearRecycler.layoutManager = LinearLayoutManager(ctx)

            // Setup seasons adapter (radio mode)
            val selectedSeasonsSet = if (dialogSeason != "All") setOf(dialogSeason) else setOf("All")
            var seasonsAdapter: AniListFilterUtils.AniListCheckboxAdapter? = null
            seasonsAdapter = AniListFilterUtils.AniListCheckboxAdapter(seasons, selectedSeasonsSet, { item, isChecked ->
                if (isChecked) {
                    dialogSeason = item
                } else {
                    dialogSeason = "All"
                }
                val newSelectedSet = setOf(dialogSeason).filterNotNull().toSet()
                dialogBinding.seasonRecycler.post {
                    seasonsAdapter?.updateSelectedSet(newSelectedSet)
                }
            }, radioMode = true)
            dialogBinding.seasonRecycler.adapter = seasonsAdapter
            dialogBinding.seasonRecycler.layoutManager = LinearLayoutManager(ctx)

            // Setup formats adapter (radio mode)
            val selectedFormatsSet = if (dialogFormat != "All") setOf(dialogFormat) else setOf("All")
            var formatsAdapter: AniListFilterUtils.AniListCheckboxAdapter? = null
            formatsAdapter = AniListFilterUtils.AniListCheckboxAdapter(formats, selectedFormatsSet, { item, isChecked ->
                if (isChecked) {
                    dialogFormat = item
                } else {
                    dialogFormat = "All"
                }
                val newSelectedSet = setOf(dialogFormat).filterNotNull().toSet()
                dialogBinding.formatRecycler.post {
                    formatsAdapter?.updateSelectedSet(newSelectedSet)
                }
            }, radioMode = true)
            dialogBinding.formatRecycler.adapter = formatsAdapter
            dialogBinding.formatRecycler.layoutManager = LinearLayoutManager(ctx)

            // Setup sort adapter (radio mode)
            val selectedSortSet = if (dialogSort != "All") setOf(dialogSort) else setOf("All")
            var sortAdapter: AniListFilterUtils.AniListCheckboxAdapter? = null
            sortAdapter = AniListFilterUtils.AniListCheckboxAdapter(sortOptions, selectedSortSet, { item, isChecked ->
                if (isChecked) {
                    dialogSort = item
                } else {
                    dialogSort = "All"
                }
                val newSelectedSet = setOf(dialogSort).filterNotNull().toSet()
                dialogBinding.sortRecycler.post {
                    sortAdapter?.updateSelectedSet(newSelectedSet)
                }
            }, radioMode = true)
            dialogBinding.sortRecycler.adapter = sortAdapter
            dialogBinding.sortRecycler.layoutManager = LinearLayoutManager(ctx)

            // Update initial counts
            dialogBinding.genresCount.text = "${dialogGenres.size}"
            dialogBinding.tagsCount.text = "${dialogTags.size}"
            // Hide count text views for single-select fields (year, season, format, sort)
            dialogBinding.yearCount.visibility = View.GONE
            dialogBinding.seasonCount.visibility = View.GONE
            dialogBinding.formatCount.visibility = View.GONE
            dialogBinding.sortCount.visibility = View.GONE

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

            // Clear button
            dialogBinding.clearButton.setOnClickListener {
                dialogGenres.clear()
                dialogTags.clear()
                dialogYear = "All"
                dialogSeason = "All"
                dialogFormat = "All"
                dialogSort = "All"
                genresAdapter.notifyDataSetChanged()
                tagsAdapter.notifyDataSetChanged()
                yearsAdapter.updateSelectedSet(setOf("All"))
                seasonsAdapter.updateSelectedSet(setOf("All"))
                formatsAdapter.updateSelectedSet(setOf("All"))
                sortAdapter.updateSelectedSet(setOf("All"))
                dialogBinding.genresCount.text = "0"
                dialogBinding.tagsCount.text = "0"
            }

            // Apply button
            dialogBinding.applyButton.setOnClickListener {
                // Validation: year requires season (but not if both are "All")
                if (dialogYear != "All" && dialogSeason == "All") {
                    Toast.makeText(ctx, "Please select a season when filtering by year", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // Update class-level variables
                selectedGenres.clear()
                selectedGenres.addAll(dialogGenres)
                selectedTags.clear()
                selectedTags.addAll(dialogTags)
                selectedYear = dialogYear
                selectedSeason = dialogSeason
                selectedFormat = dialogFormat
                selectedSort = dialogSort

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

            android.util.Log.d("BrowseFragment", "loadAniListResults: Calling aniListApi.getMediaByGenre with page=$currentAniListPage, seasonYear=$seasonYear, season=$season, format=$format, sort=$sort, searchQuery=$searchQuery")

            val response: AniListApi.MediaByGenreResponse? = aniListApi.getMediaByGenre(
                selectedGenres.toList(),
                selectedTags.toList(),
                currentAniListPage,
                seasonYear,
                season,
                format,
                sort,
                searchQuery
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
