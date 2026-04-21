package com.lagradost.cloudstream3.ui.search

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.DialogInterface
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKeys
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.databinding.FragmentSearchBinding
import com.lagradost.cloudstream3.databinding.HomeSelectMainpageBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.BaseAdapter
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.home.HomeFragment
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.aniListApi
import com.lagradost.cloudstream3.syncproviders.SyncRepo
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.bindChips
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.currentSpan
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.loadHomepageList
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.updateChips
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.home.ParentItemAdapter
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.AppContextUtils.filterSearchResultByFilmQuality
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiProviderLangSettings
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiSettings
import com.lagradost.cloudstream3.utils.AppContextUtils.ownHide
import com.lagradost.cloudstream3.utils.AppContextUtils.ownShow
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.attachBackPressedCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.detachBackPressedCallback
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.getSpanCount
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore

class SearchFragment : BaseFragment<FragmentSearchBinding>(
    BaseFragment.BindingCreator.Bind(FragmentSearchBinding::bind)
) {
    companion object {
        fun List<SearchResponse>.filterSearchResponse(): List<SearchResponse> {
            return this.filter { response ->
                if (response is AnimeSearchResponse) {
                    val status = response.dubStatus
                    (status.isNullOrEmpty()) || (status.any {
                        APIRepository.dubStatusActive.contains(it)
                    })
                } else {
                    true
                }
            }
        }

        fun List<SearchResponse>.filterByGenre(genre: String?): List<SearchResponse> {
            return if (genre == null) this
            else this.filter { it.tags?.contains(genre) == true }
        }

        const val SEARCH_QUERY = "search_query"

        fun newInstance(query: String): Bundle {
            return Bundle().apply {
                if (query.isNotBlank()) putString(SEARCH_QUERY, query)
            }
        }
    }

    private val searchViewModel: SearchViewModel by activityViewModels()
    private var bottomSheetDialog: BottomSheetDialog? = null

    private val speechRecognizerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                val matches = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                if (!matches.isNullOrEmpty()) {
                    val recognizedText = matches[0]
                    binding?.mainSearch?.setQuery(recognizedText, true)
                }
            }
        }

    override fun pickLayout(): Int? =
        if (isLayout(TV or EMULATOR)) R.layout.fragment_search_tv else R.layout.fragment_search

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        activity?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
        bottomSheetDialog?.ownShow()
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onDestroyView() {
        hideKeyboard()
        bottomSheetDialog?.ownHide()
        activity?.detachBackPressedCallback("SearchFragment")
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        afterPluginsLoadedEvent += ::reloadRepos
    }

    override fun onStop() {
        super.onStop()
        afterPluginsLoadedEvent -= ::reloadRepos
    }

    var selectedSearchTypes = mutableListOf<TvType>()
    var selectedApis = mutableSetOf<String>()
    var availableGenres = listOf<String>()
    var selectedGenre: String? = null
    var currentSearchResults: Map<String, com.lagradost.cloudstream3.ui.search.ExpandableSearchList>? = null
    var anilistGenreMap: Map<String, List<String>> = emptyMap()
    
    // AniList Browse Mode State
    var isShowingAniListResults: Boolean = false
    var currentAniListPage: Int = 1
    var currentSelectedGenre: String? = null
    var hasMoreAniListResults: Boolean = false
    var anilistBrowseResults: List<SearchResponse> = emptyList()
    
    // Multi-selection state for genres and tags
    private var selectedGenres: MutableSet<String> = mutableSetOf()
    private var selectedTags: MutableSet<String> = mutableSetOf()
    
    // Cache for AniList genres and tags (separate raw API response from processed strings)
    private var cachedAniListGenres: List<String>? = null
    private var cachedRawTags: List<AniListApi.MediaTag>? = null


    private fun loadAniListResultsByGenreWithFilters(
        seasonYear: Int?,
        season: String?,
        format: String?,
        genres: List<String>,
        tags: List<String>,
        page: Int = 1
    ) {
        ioSafe {
            main {
                binding?.searchLoadingBar?.alpha = 1f
            }

            val response = aniListApi.getMediaByGenre(genres, tags, page, seasonYear, season, format)
            val mediaItems = response?.data?.page?.media ?: emptyList()
            val hasNextPage = response?.data?.page?.pageInfo?.hasNextPage ?: false

            val searchResponses = mediaItems.mapNotNull { it.toSearchResponse() }

            main {
                binding?.searchLoadingBar?.alpha = 0f

                if (page == 1) {
                    anilistBrowseResults = searchResponses
                } else {
                    anilistBrowseResults = anilistBrowseResults + searchResponses
                }

                hasMoreAniListResults = hasNextPage
                displayAniListResults()
            }
        }
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

    private fun loadAniListResultsByGenre(genres: List<String>, tags: List<String> = emptyList(), page: Int) {
        ioSafe {
            main {
                binding?.searchLoadingBar?.alpha = 1f
            }

            val response = aniListApi.getMediaByGenre(genres, tags, page)
            val mediaItems = response?.data?.page?.media ?: emptyList()
            val hasNextPage = response?.data?.page?.pageInfo?.hasNextPage ?: false

            val searchResponses = mediaItems.mapNotNull { it.toSearchResponse() }

            main {
                binding?.searchLoadingBar?.alpha = 0f

                if (page == 1) {
                    anilistBrowseResults = searchResponses
                } else {
                    anilistBrowseResults = anilistBrowseResults + searchResponses
                }

                hasMoreAniListResults = hasNextPage

                // Display AniList results in the search results view
                displayAniListResults()
            }
        }
    }

    private fun displayAniListResults() {
        val adapter = binding?.searchAutofitResults?.adapter as? SearchAdapter ?: return
        
        // Add "Load More" button if there are more results
        val displayList = if (hasMoreAniListResults) {
            anilistBrowseResults + createLoadMoreItem()
        } else {
            anilistBrowseResults
        }

        adapter.submitList(displayList)
    }

    private fun createLoadMoreItem(): SearchResponse {
        @Suppress("DEPRECATION_ERROR")
        return AnimeSearchResponse(
            name = getString(R.string.anilist_browse_load_more),
            url = "",
            apiName = "AniList",
            type = TvType.Anime,
            id = -1,
            posterUrl = null
        )
    }

    private fun isAniListLoggedIn(): Boolean {
        return AccountManager.accounts(aniListApi.idPrefix).isNotEmpty()
    }

    private fun showAniListLoginPrompt() {
        activity?.let { ctx ->
            val builder = AlertDialog.Builder(ctx)
            builder.setTitle(getString(R.string.genre_filter_anilist_login_required))
            builder.setMessage(getString(R.string.genre_filter_anilist_login_message))
            builder.setPositiveButton(getString(R.string.genre_filter_anilist_login_button)) { dialog, _ ->
                // Navigate to AniList settings
                ctx.startActivity(Intent(ctx, com.lagradost.cloudstream3.ui.settings.SettingsAccount::class.java))
                dialog.dismiss()
            }
            builder.setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.dismiss()
            }
            builder.show()
        }
    }


    private fun isAnimeProvider(providerName: String): Boolean {
        val api = getApiFromNameNull(providerName) ?: return false
        return api.supportedTypes.any { type ->
            type == TvType.Anime || type == TvType.OVA || type == TvType.AnimeMovie
        }
    }

    private suspend fun loadAniListGenresForAnime(
        searchResults: List<SearchResponse>
    ): Map<String, List<String>> {
        return coroutineScope {
            val semaphore = Semaphore(5)
            val genreMap = mutableMapOf<String, List<String>>()

            android.util.Log.d("GenreFilter", "loadAniListGenresForAnime: ${searchResults.size} search results")

            val animeResults = searchResults.filter { result ->
                result.type == TvType.Anime || result.type == TvType.OVA || result.type == TvType.AnimeMovie
            }

            android.util.Log.d("GenreFilter", "loadAniListGenresForAnime: ${animeResults.size} anime results")

            val auth = AccountManager.accounts(aniListApi.idPrefix).firstOrNull()
            android.util.Log.d("GenreFilter", "loadAniListGenresForAnime: auth=${auth != null}")

            animeResults.map { result ->
                async {
                    semaphore.acquire()
                    try {
                        android.util.Log.d("GenreFilter", "Searching AniList for: ${result.name}")
                        val anilistSearch = aniListApi.search(auth, result.name)
                        android.util.Log.d("GenreFilter", "AniList search result: ${anilistSearch?.size} results")
                        if (anilistSearch != null && anilistSearch.size > 0) {
                            val anilistId = anilistSearch[0].syncId
                            android.util.Log.d("GenreFilter", "Loading AniList metadata for id: $anilistId")
                            val metadata = aniListApi.load(auth, anilistId)
                            val genres = metadata?.genres
                            android.util.Log.d("GenreFilter", "AniList genres for ${result.name}: ${genres?.size ?: 0}")
                            if (genres != null) {
                                genreMap[result.name] = genres
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("GenreFilter", "Error loading AniList metadata for ${result.name}: $e")
                        logError(e)
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll()

            android.util.Log.d("GenreFilter", "loadAniListGenresForAnime: loaded ${genreMap.size} genre mappings")
            genreMap
        }
    }

    /**
     * Will filter all providers by preferred media and selectedSearchTypes.
     * If that results in no available providers then only filter
     * providers by preferred media
     **/
    fun search(query: String?) {
        if (query == null) return
        selectedGenre = null // Clear genre filter on new search
        // don't resume state from prev search
        (binding?.searchMasterRecycler?.adapter as? BaseAdapter<*, *>)?.clearState()
        context?.let { ctx ->
            val default = enumValues<TvType>().sorted().filter { it != TvType.NSFW }
                .map { it.ordinal.toString() }.toSet()
            val preferredTypes = (PreferenceManager.getDefaultSharedPreferences(ctx)
                .getStringSet(this.getString(R.string.prefer_media_type_key), default)
                ?.ifEmpty { default } ?: default)
                .mapNotNull { it.toIntOrNull() ?: return@mapNotNull null }

            val settings = ctx.getApiSettings()

            val notFilteredBySelectedTypes = selectedApis.filter { name ->
                settings.contains(name)
            }.map { name ->
                name to getApiFromNameNull(name)?.supportedTypes
            }.filter { (_, types) ->
                types?.any { preferredTypes.contains(it.ordinal) } == true
            }

            searchViewModel.searchAndCancel(
                query = query,
                providersActive = notFilteredBySelectedTypes.filter { (_, types) ->
                    types?.any { selectedSearchTypes.contains(it) } == true
                }.ifEmpty { notFilteredBySelectedTypes }.map { it.first }.toSet()
            )
        }
    }

    fun filterCurrentResultsByGenre(genre: String?) {
        // Filter the parent adapter results
        val list = currentSearchResults ?: return
        val adapter = binding?.searchMasterRecycler?.adapter as? ParentItemAdapter ?: return

        val pinnedOrder = DataStoreHelper.pinnedProviders.reversedArray()
        val sortedList = list.entries.sortedWith(compareBy<Map.Entry<String, com.lagradost.cloudstream3.ui.search.ExpandableSearchList>> { entry ->
            val providerName = entry.key
            val index = pinnedOrder.indexOf(providerName)
            if (index == -1) Int.MAX_VALUE else index
        })

        val filteredItems = sortedList.map { entry ->
            val providerName = entry.key
            val providerData = entry.value
            val dataList = providerData.list
            val dataListFiltered = if (genre == null) dataList else dataList.filter { item ->
                // Check tags from search response
                val hasTag = item.tags?.contains(genre) == true
                // Check AniList genres
                val hasAniListGenre = anilistGenreMap[item.name]?.contains(genre) == true
                hasTag || hasAniListGenre
            }

            val homePageList = HomePageList(
                providerName,
                dataListFiltered
            )

            HomeViewModel.ExpandableHomepageList(
                homePageList,
                providerData.currentPage,
                providerData.hasNext
            )
        }

        adapter.submitList(filteredItems)
    }

    // Null if defined as a variable
    // This needs to be run after view created

    private fun reloadRepos(success: Boolean = false) = main {
        searchViewModel.reloadRepos()
        context?.filterProviderByPreferredMedia()?.let { validAPIs ->
            bindChips(
                binding?.tvtypesChipsScroll?.tvtypesChips,
                selectedSearchTypes,
                validAPIs.flatMap { api -> api.supportedTypes }.distinct()
            ) { list ->
                if (selectedSearchTypes.toSet() != list.toSet()) {
                    DataStoreHelper.searchPreferenceTags = list
                    selectedSearchTypes.clear()
                    selectedSearchTypes.addAll(list)
                    search(binding?.mainSearch?.query?.toString())
                }
            }
        }
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padBottom = isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )

        // Fix grid
        currentSpan = view.context.getSpanCount()
        binding?.searchAutofitResults?.spanCount = currentSpan
        HomeFragment.configEvent.invoke()
    }

    override fun onBindingCreated(
        binding: FragmentSearchBinding,
        savedInstanceState: Bundle?
    ) {
        reloadRepos()
        binding.apply {
            val adapter =
                SearchAdapter(
                    searchAutofitResults,
                ) { callback ->
                    // Handle AniList browse mode clicks
                    if (isShowingAniListResults) {
                        val clickedItem = callback.card
                        if (clickedItem.id == -1) {
                            // Load more AniList results
                            currentAniListPage++
                            loadAniListResultsByGenre(selectedGenres.toList(), selectedTags.toList(), currentAniListPage)
                        } else {
                            // Open result in player
                            SearchHelper.handleSearchClickCallback(callback)
                        }
                    } else {
                        // Normal provider search handling
                        SearchHelper.handleSearchClickCallback(callback)
                    }
                }

            searchRoot.findViewById<TextView>(androidx.appcompat.R.id.search_src_text)?.tag =
                "tv_no_focus_tag"
            searchAutofitResults.setRecycledViewPool(SearchAdapter.sharedPool)
            searchAutofitResults.adapter = adapter
            searchLoadingBar.alpha = 0f
        }

        binding.voiceSearch.setOnClickListener { searchView ->
            searchView?.context?.let { ctx ->
                try {
                    if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                        showToast(R.string.speech_recognition_unavailable)
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
                    // launch may throw
                    showToast(R.string.speech_recognition_unavailable)
                }
            }
        }

        val searchExitIcon =
            binding.mainSearch.findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn)

        selectedApis = DataStoreHelper.searchPreferenceProviders.toMutableSet()

        binding.searchFilter.setOnClickListener { searchView ->
            searchView?.context?.let { ctx ->
                val validAPIs = ctx.filterProviderByPreferredMedia(hasHomePageIsRequired = false)
                var currentValidApis = listOf<MainAPI>()
                val currentSelectedApis = if (selectedApis.isEmpty()) validAPIs.map { it.name }
                    .toMutableSet() else selectedApis

                val builder =
                    BottomSheetDialog(ctx)

                builder.behavior.state = BottomSheetBehavior.STATE_EXPANDED

                val selectMainpageBinding: HomeSelectMainpageBinding =
                    HomeSelectMainpageBinding.inflate(
                        builder.layoutInflater,
                        null,
                        false
                    )
                builder.setContentView(selectMainpageBinding.root)
                builder.show()
                builder.let { dialog ->
                    val previousSelectedApis = selectedApis.toSet()
                    val previousSelectedSearchTypes = selectedSearchTypes.toSet()

                    val isMultiLang = ctx.getApiProviderLangSettings().let { set ->
                        set.size > 1 || set.contains(AllLanguagesName)
                    }

                    val cancelBtt = dialog.findViewById<MaterialButton>(R.id.cancel_btt)
                    val applyBtt = dialog.findViewById<MaterialButton>(R.id.apply_btt)

                    val listView = dialog.findViewById<ListView>(R.id.listview1)
                    val arrayAdapter = ArrayAdapter<String>(ctx, R.layout.sort_bottom_single_choice)
                    listView?.adapter = arrayAdapter
                    listView?.choiceMode = AbsListView.CHOICE_MODE_MULTIPLE

                    // Genre filter button inside provider selector
                    val genreFilterBtn = dialog.findViewById<ImageView>(R.id.genre_filter)
                    genreFilterBtn?.isVisible = currentSelectedApis.any { isAnimeProvider(it) }

                    listView?.setOnItemClickListener { _, _, i, _ ->
                        if (currentValidApis.isNotEmpty()) {
                            val api = currentValidApis[i].name
                            if (currentSelectedApis.contains(api)) {
                                listView.setItemChecked(i, false)
                                currentSelectedApis -= api
                            } else {
                                listView.setItemChecked(i, true)
                                currentSelectedApis += api
                            }
                            // Update genre filter button visibility based on selected providers
                            genreFilterBtn?.isVisible = currentSelectedApis.any { isAnimeProvider(it) }
                        }
                    }

                    fun updateList(types: List<TvType>) {
                        DataStoreHelper.searchPreferenceTags = types

                        arrayAdapter.clear()
                        currentValidApis = validAPIs.filter { api ->
                            api.supportedTypes.any {
                                types.contains(it)
                            }
                        }.sortedBy { it.name.lowercase() }

                        val names = currentValidApis.map {
                            if (isMultiLang) "${
                                SubtitleHelper.getFlagFromIso(
                                    it.lang
                                )?.plus(" ") ?: ""
                            }${it.name}" else it.name
                        }
                        for ((index, api) in currentValidApis.map { it.name }.withIndex()) {
                            listView?.setItemChecked(index, currentSelectedApis.contains(api))
                        }

                        //arrayAdapter.notifyDataSetChanged()
                        arrayAdapter.addAll(names)
                        arrayAdapter.notifyDataSetChanged()
                    }

                    bindChips(
                        selectMainpageBinding.tvtypesChipsScroll.tvtypesChips,
                        selectedSearchTypes,
                        validAPIs.flatMap { api -> api.supportedTypes }.distinct()
                    ) { list ->
                        updateList(list)

                        // refresh selected chips in main chips
                        if (selectedSearchTypes.toSet() != list.toSet()) {
                            selectedSearchTypes.clear()
                            selectedSearchTypes.addAll(list)
                            updateChips(
                                binding.tvtypesChipsScroll.tvtypesChips,
                                selectedSearchTypes
                            )

                        }
                    }


                    cancelBtt?.setOnClickListener {
                        dialog.dismissSafe()
                    }

                    // Genre filter button click listener
                    genreFilterBtn?.setOnClickListener {
                        // Check if any selected providers are anime-focused
                        val hasAnimeProviders = currentSelectedApis.any { isAnimeProvider(it) }

                        if (hasAnimeProviders && !isAniListLoggedIn()) {
                            showAniListLoginPrompt()
                            return@setOnClickListener
                        }

                        // AniList Browse Mode dialog removed - filter UI only on homescreen
                    }

                    applyBtt?.setOnClickListener {
                        //if (currentApiName != selectedApiName) {
                        //    currentApiName?.let(callback)
                        //}
                        dialog.dismissSafe()
                    }

                    dialog.setOnDismissListener {
                        DataStoreHelper.searchPreferenceProviders = currentSelectedApis.toList()
                        selectedApis = currentSelectedApis

                        // run search when dialog is close
                        if (previousSelectedApis != selectedApis.toSet() || previousSelectedSearchTypes != selectedSearchTypes.toSet()) {
                            search(binding.mainSearch.query.toString())
                        }
                    }
                    updateList(selectedSearchTypes.toList())
                }
            }
        }

        android.util.Log.d("GenreFilter", "Setting up genre filter click listener, button exists=${binding.genreFilter != null}")

        binding.genreFilter.setOnClickListener {
            android.util.Log.d("GenreFilter", "Genre filter button clicked! isVisible=${binding.genreFilter.isVisible}, isEnabled=${binding.genreFilter.isEnabled}")

            // Check if any selected providers are anime-focused
            val hasAnimeProviders = selectedApis.any { isAnimeProvider(it) }

            if (hasAnimeProviders && !isAniListLoggedIn()) {
                showAniListLoginPrompt()
                return@setOnClickListener
            }

            // Setup AniList filter dropdowns removed - filter UI only on homescreen

            val settingsManager = context?.let { PreferenceManager.getDefaultSharedPreferences(it) }
        val isAdvancedSearch = settingsManager?.getBoolean("advanced_search", true) ?: true
        val isSearchSuggestionsEnabled = settingsManager?.getBoolean("search_suggestions_enabled", true) ?: true

        selectedSearchTypes = DataStoreHelper.searchPreferenceTags.toMutableList()

        if (!isLayout(PHONE)) {
            binding.searchFilter.isFocusable = true
            binding.searchFilter.isFocusableInTouchMode = true
        }
        
        // Hide suggestions when search view loses focus (phone only)
        if (isLayout(PHONE)) {
            binding.mainSearch.setOnQueryTextFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    searchViewModel.clearSuggestions()
                }
            }
        }


        binding.mainSearch.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                search(query)
                searchViewModel.clearSuggestions()

                binding.mainSearch.let {
                    hideKeyboard(it)
                }

                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                //searchViewModel.quickSearch(newText)
                val showHistory = newText.isBlank()
                if (showHistory) {
                    searchViewModel.clearSearch()
                    searchViewModel.updateHistory()
                    searchViewModel.clearSuggestions()
                } else {
                    // Fetch suggestions when user is typing (if enabled)
                    if (isSearchSuggestionsEnabled) {
                        searchViewModel.fetchSuggestions(newText)
                    }
                }
                binding.apply {
                    searchHistoryRecycler.isVisible = showHistory
                    searchMasterRecycler.isVisible = !showHistory && isAdvancedSearch
                    searchAutofitResults.isVisible = !showHistory && !isAdvancedSearch
                    // Hide suggestions when showing history or showing search results
                    searchSuggestionsRecycler.isVisible = !showHistory && isSearchSuggestionsEnabled
                }

                return true
            }
        })

        observe(searchViewModel.searchResponse) {
            when (it) {
                is Resource.Success -> {
                    it.value.let { data ->
                        val list = data.list
                        if (list.isNotEmpty()) {
                            (binding.searchAutofitResults.adapter as? SearchAdapter)?.submitList(
                                list
                            )
                        }
                    }
                    searchExitIcon?.alpha = 1f
                    binding.searchLoadingBar.alpha = 0f
                }

                is Resource.Failure -> {
                    // Toast.makeText(activity, "Server error", Toast.LENGTH_LONG).show()
                    searchExitIcon?.alpha = 1f
                    binding.searchLoadingBar.alpha = 0f
                }

                is Resource.Loading -> {
                    searchExitIcon?.alpha = 0f
                    binding.searchLoadingBar.alpha = 1f
                }
            }
        }

        val listLock = ReentrantLock()
        observe(searchViewModel.currentSearch) { list ->
            try {
                // https://stackoverflow.com/questions/6866238/concurrent-modification-exception-adding-to-an-arraylist
                listLock.lock()

                currentSearchResults = list // Store current results

                val pinnedOrder = DataStoreHelper.pinnedProviders.reversedArray()

                val sortedList = list.toList().sortedWith(compareBy { (providerName, _) ->
                    val index = pinnedOrder.indexOf(providerName)
                    if (index == -1) Int.MAX_VALUE else index
                })

                // Extract genres from selected providers only
                val selectedProviderNames = selectedApis.toSet()
                availableGenres = list
                    .filter { (providerName, _) -> selectedProviderNames.contains(providerName) }
                    .flatMap { (_, providerData) ->
                        providerData.list.flatMap { it.tags ?: emptyList() }
                    }
                    .distinct()
                    .sorted()

                val hasAnimeProviders = selectedApis.any { isAnimeProvider(it) }
                binding.genreFilter.isVisible = hasAnimeProviders || availableGenres.isNotEmpty()

                (binding.searchMasterRecycler.adapter as? ParentItemAdapter)?.apply {
                    val newItems = sortedList.map { (providerName, providerData) ->
                        val dataList = providerData.list
                        val dataListFiltered =
                            context?.filterSearchResultByFilmQuality(dataList) ?: dataList

                        val homePageList = HomePageList(
                            providerName,
                            dataListFiltered
                        )

                        HomeViewModel.ExpandableHomepageList(
                            homePageList,
                            providerData.currentPage,
                            providerData.hasNext
                        )
                    }

                    submitList(newItems)
                    //notifyDataSetChanged()
                }
            } catch (e: Exception) {
                logError(e)
            } finally {
                listLock.unlock()
            }
        }


        /*main_search.setOnQueryTextFocusChangeListener { _, b ->
            if (b) {
                // https://stackoverflow.com/questions/12022715/unable-to-show-keyboard-automatically-in-the-searchview
                showInputMethod(view.findFocus())
            }
        }*/
        //main_search.onActionViewExpanded()*/

        val masterAdapter =
            ParentItemAdapter(id = "masterAdapter".hashCode(), { callback ->
                SearchHelper.handleSearchClickCallback(callback)
            }, { item ->
                bottomSheetDialog = activity?.loadHomepageList(item, dismissCallback = {
                    bottomSheetDialog = null
                }, expandCallback = { name -> searchViewModel.expandAndReturn(name) })
            }, expandCallback = { name ->
                ioSafe {
                    searchViewModel.expandAndReturn(name)
                }
            })

        val historyAdapter = SearchHistoryAdaptor { click ->
            val searchItem = click.item
            when (click.clickAction) {
                SEARCH_HISTORY_OPEN -> {
                    if (searchItem == null) return@SearchHistoryAdaptor
                    searchViewModel.clearSearch()
                    if (searchItem.type.isNotEmpty())
                        updateChips(
                            binding.tvtypesChipsScroll.tvtypesChips,
                            searchItem.type.toMutableList()
                        )
                    binding.mainSearch.setQuery(searchItem.searchText, true)
                }

                SEARCH_HISTORY_REMOVE -> {
                    if (searchItem == null) return@SearchHistoryAdaptor
                    removeKey("$currentAccount/$SEARCH_HISTORY_KEY", searchItem.key)
                    searchViewModel.updateHistory()
                }
                
                SEARCH_HISTORY_CLEAR -> {
                    // Show confirmation dialog (from footer button)
                    activity?.let { ctx ->
                        val builder: AlertDialog.Builder = AlertDialog.Builder(ctx)
                        val dialogClickListener =
                            DialogInterface.OnClickListener { _, which ->
                                when (which) {
                                    DialogInterface.BUTTON_POSITIVE -> {
                                        removeKeys("$currentAccount/$SEARCH_HISTORY_KEY")
                                        searchViewModel.updateHistory()
                                    }

                                    DialogInterface.BUTTON_NEGATIVE -> {
                                    }
                                }
                            }

                        try {
                            builder.setTitle(R.string.clear_history).setMessage(
                                ctx.getString(R.string.delete_message).format(
                                    ctx.getString(R.string.history)
                                )
                            )
                                .setPositiveButton(R.string.sort_clear, dialogClickListener)
                                .setNegativeButton(R.string.cancel, dialogClickListener)
                                .show().setDefaultFocus()
                        } catch (e: Exception) {
                            logError(e)
                        }
                    }
                }

                else -> {
                    // wth are you doing???
                }
            }
        }

        val suggestionAdapter = SearchSuggestionAdapter { callback ->
            when (callback.clickAction) {
                SEARCH_SUGGESTION_CLICK -> {
                    // Search directly
                    binding.mainSearch.setQuery(callback.suggestion, true)
                    searchViewModel.clearSuggestions()
                }
                SEARCH_SUGGESTION_FILL -> {
                    // Fill the search box without searching
                    binding.mainSearch.setQuery(callback.suggestion, false)
                }
                SEARCH_SUGGESTION_CLEAR -> {
                    // Clear suggestions (from footer button)
                    searchViewModel.clearSuggestions()
                }
            }
        }

        binding.apply {
            searchHistoryRecycler.adapter = historyAdapter
            searchHistoryRecycler.setLinearListLayout(isHorizontal = false, nextRight = FOCUS_SELF)
            //searchHistoryRecycler.layoutManager = GridLayoutManager(context, 1)

            // Setup suggestions RecyclerView
            searchSuggestionsRecycler.adapter = suggestionAdapter
            searchSuggestionsRecycler.layoutManager = LinearLayoutManager(context)

            searchMasterRecycler.setRecycledViewPool(ParentItemAdapter.sharedPool)
            searchMasterRecycler.adapter = masterAdapter
            //searchMasterRecycler.setLinearListLayout(isHorizontal = false, nextRight = FOCUS_SELF)

            searchMasterRecycler.layoutManager = GridLayoutManager(context, 1)

            // Automatically search the specified query, this allows the app search to launch from intent
            var sq =
                arguments?.getString(SEARCH_QUERY) ?: savedInstanceState?.getString(SEARCH_QUERY)
            if (sq.isNullOrBlank()) {
                sq = MainActivity.nextSearchQuery
            }

            sq?.let { query ->
                if (query.isBlank()) return@let
                mainSearch.setQuery(query, true)
                // Clear the query as to not make it request the same query every time the page is opened
                arguments?.remove(SEARCH_QUERY)
                savedInstanceState?.remove(SEARCH_QUERY)
                MainActivity.nextSearchQuery = null
            }
        }

        observe(searchViewModel.currentHistory) { list ->
            (binding.searchHistoryRecycler.adapter as? SearchHistoryAdaptor?)?.submitList(list)
             // Scroll to top to show newest items (list is sorted by newest first)
            if (list.isNotEmpty()) {
                binding.searchHistoryRecycler.scrollToPosition(0)
            }
        }

        // Observe search suggestions
        observe(searchViewModel.searchSuggestions) { suggestions ->
            val hasSuggestions = suggestions.isNotEmpty()
            binding.searchSuggestionsRecycler.isVisible = hasSuggestions
            (binding.searchSuggestionsRecycler.adapter as? SearchSuggestionAdapter?)?.submitList(suggestions)
            
            // On non-phone layouts, redirect focus and handle back button
            if (!isLayout(PHONE)) {
                if (hasSuggestions) {
                    binding.tvtypesChipsScroll.tvtypesChips.root.nextFocusDownId = R.id.search_suggestions_recycler
                    // Attach back button callback to clear suggestions
                    activity?.attachBackPressedCallback("SearchFragment") {
                        searchViewModel.clearSuggestions()
                    }
                } else {
                    // Reset to default focus target (history)
                    binding.tvtypesChipsScroll.tvtypesChips.root.nextFocusDownId = R.id.search_history_recycler
                    // Detach back button callback when no suggestions
                    activity?.detachBackPressedCallback("SearchFragment")
                }
            }
        }
        }

        searchViewModel.updateHistory()
    }
}
