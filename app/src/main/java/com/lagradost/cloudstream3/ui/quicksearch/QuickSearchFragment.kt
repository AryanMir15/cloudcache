package com.lagradost.cloudstream3.ui.quicksearch

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import com.google.android.material.chip.ChipGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.QuickSearchBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.home.HomeFragment
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.loadHomepageList
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.home.ParentItemAdapter
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.ui.search.SearchViewModel
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.AppContextUtils.filterSearchResultByFilmQuality
import com.lagradost.cloudstream3.utils.AppContextUtils.isRecyclerScrollable
import com.lagradost.cloudstream3.utils.AppContextUtils.ownShow
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.getSpanCount
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.UIHelper.popCurrentPage
import java.util.concurrent.locks.ReentrantLock
import android.app.AlertDialog
import android.content.Intent
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.aniListApi
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import androidx.recyclerview.widget.StaggeredGridLayoutManager // Added import
import androidx.navigation.NavOptions

class QuickSearchFragment : BaseFragment<QuickSearchBinding>(
    BaseFragment.BindingCreator.Inflate(QuickSearchBinding::inflate)
) {
    // ... rest of the code remains the same ...
    companion object {
        const val AUTOSEARCH_KEY = "autosearch"
        const val PROVIDER_KEY = "providers"
        const val ANILIST_GENRES_KEY = "anilist_genres"
        const val ANILIST_TAGS_KEY = "anilist_tags"
        const val ANILIST_SEASON_YEAR_KEY = "anilist_season_year"
        const val ANILIST_SEASON_KEY = "anilist_season"
        const val ANILIST_FORMAT_KEY = "anilist_format"
        const val ANILIST_SORT_KEY = "anilist_sort"

        fun pushSearch(
            autoSearch: String? = null,
            providers: Array<String>? = null
        ) {
            pushSearch(activity, autoSearch, providers)
        }

        fun pushSearch(
            activity: Activity?,
            autoSearch: String? = null,
            providers: Array<String>? = null
        ) {
            activity?.let { ctx ->
                // Navigate to search tab with query as argument
                ctx.navigate(R.id.navigation_search, Bundle().apply {
                    if (autoSearch != null) {
                        putString(
                            com.lagradost.cloudstream3.ui.search.SearchFragment.SEARCH_QUERY,
                            autoSearch.trim()
                                .removeSuffix("(DUB)")
                                .removeSuffix("(SUB)")
                                .removeSuffix("(Dub)")
                                .removeSuffix("(Sub)").trim()
                        )
                    }
                })
            }
        }

        fun pushSearchWithAniListFilters(
            activity: Activity?,
            genres: Array<String>? = null,
            tags: Array<String>? = null,
            seasonYear: Int? = null,
            season: String? = null,
            format: String? = null,
            sort: String? = null
        ) {
            activity.navigate(R.id.global_to_navigation_quick_search, Bundle().apply {
                genres?.let {
                    putStringArray(ANILIST_GENRES_KEY, it)
                }
                tags?.let {
                    putStringArray(ANILIST_TAGS_KEY, it)
                }
                seasonYear?.let {
                    putInt(ANILIST_SEASON_YEAR_KEY, it)
                }
                season?.let {
                    putString(ANILIST_SEASON_KEY, it)
                }
                format?.let {
                    putString(ANILIST_FORMAT_KEY, it)
                }
                sort?.let {
                    putString(ANILIST_SORT_KEY, it)
                }
            })
        }

        var clickCallback: ((SearchClickCallback) -> Unit)? = null
    }

    private var providers: Set<String>? = null
    private lateinit var searchViewModel: SearchViewModel

    private var bottomSheetDialog: BottomSheetDialog? = null

    var availableGenres = listOf<String>()
    var selectedGenre: String? = null
    var anilistGenreMap: Map<String, List<String>> = emptyMap()
    var currentSearchResults: Map<String, com.lagradost.cloudstream3.ui.search.ExpandableSearchList>? = null
    
    // AniList Browse Mode State
    var isShowingAniListResults: Boolean = false
    var currentAniListPage: Int = 1
    var currentSelectedGenre: String? = null
    var hasMoreAniListResults: Boolean = false
    var anilistBrowseResults: List<SearchResponse> = emptyList()
    
    // Cache for AniList genres and tags (separate raw API response from processed strings)
    private var cachedAniListGenres: List<String>? = null
    private var cachedRawTags: List<AniListApi.MediaTag>? = null
    
    // Multi-selection state for genres and tags
    private var selectedGenres: MutableSet<String> = mutableSetOf()
    private var selectedTags: MutableSet<String> = mutableSetOf()
    
    // Filter state for QuickSearchFragment
    private var selectedYear: String? = "All"
    private var selectedSeason: String? = "All"
    private var selectedFormat: String? = "All"
    private var selectedSort: String? = "All"


    private fun displayAniListResults() {
        val adapter = binding?.quickSearchAutofitResults?.adapter as? SearchAdapter ?: return

        // Show/hide FAB based on whether there are more results
        binding?.loadMoreFab?.visibility = if (hasMoreAniListResults) View.VISIBLE else View.GONE

        // Don't add load more item to the list anymore
        adapter.submitList(anilistBrowseResults)
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

    private fun loadAniListResultsByGenre(
        genres: List<String>,
        tags: List<String> = emptyList(),
        page: Int,
        seasonYear: Int? = null,
        season: String? = null,
        format: String? = null,
        sort: String? = "POPULARITY_DESC",
        search: String? = null
    ) {
        android.util.Log.d("GenreFilter", "loadAniListResultsByGenre called: genres=$genres, tags=$tags, page=$page, seasonYear=$seasonYear, season=$season, format=$format, sort=$sort, search=$search")
        ioSafe {
            main {
                binding?.quickSearchLoadingBar?.alpha = 1f
            }

            android.util.Log.d("GenreFilter", "loadAniListResultsByGenre: Calling aniListApi.getMediaByGenre")
            val response = aniListApi.getMediaByGenre(genres, tags, page, seasonYear, season, format, sort, search)
            android.util.Log.d("GenreFilter", "loadAniListResultsByGenre: API response=$response")
            val mediaItems = response?.data?.page?.media ?: emptyList()
            val hasNextPage = response?.data?.page?.pageInfo?.hasNextPage ?: false

            android.util.Log.d("GenreFilter", "loadAniListResultsByGenre: mediaItems count=${mediaItems.size}, hasNextPage=$hasNextPage")

            val searchResponses = mediaItems.mapNotNull { it.toSearchResponse() }
            android.util.Log.d("GenreFilter", "loadAniListResultsByGenre: searchResponses count=${searchResponses.size}")

            main {
                binding?.quickSearchLoadingBar?.alpha = 0f

                if (page == 1) {
                    anilistBrowseResults = searchResponses
                    android.util.Log.d("GenreFilter", "loadAniListResultsByGenre: Set anilistBrowseResults to ${searchResponses.size} items (page 1)")
                } else {
                    anilistBrowseResults = anilistBrowseResults + searchResponses
                    android.util.Log.d("GenreFilter", "loadAniListResultsByGenre: Appended ${searchResponses.size} items to anilistBrowseResults (page $page)")
                }

                hasMoreAniListResults = hasNextPage

                // Show/hide FAB based on whether there are more results
                binding?.loadMoreFab?.visibility = if (hasMoreAniListResults) View.VISIBLE else View.GONE

                android.util.Log.d("GenreFilter", "loadAniListResultsByGenre: displayList size=${anilistBrowseResults.size}, isShowingAniListResults=$isShowingAniListResults")

                val adapter = binding?.quickSearchAutofitResults?.adapter as? SearchAdapter
                android.util.Log.d("GenreFilter", "loadAniListResultsByGenre: adapter=$adapter")
                adapter?.submitList(anilistBrowseResults)
                android.util.Log.d("GenreFilter", "loadAniListResultsByGenre: submitList called")
            }
        }
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(view)

        // Fix grid
        HomeFragment.currentSpan = view.context.getSpanCount()
        binding?.quickSearchAutofitResults?.spanCount = HomeFragment.currentSpan
        HomeFragment.configEvent.invoke()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        activity?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
        searchViewModel = ViewModelProvider(this)[SearchViewModel::class.java]
        bottomSheetDialog?.ownShow()
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onDestroy() {
        super.onDestroy()
        clickCallback = null
    }

    fun search(context: Context?, query: String, isQuickSearch: Boolean): Boolean {
        // Check if AniList filters are selected - if so, use AniList search with filters
        val hasAniListFilters = selectedGenres.isNotEmpty() || 
                                selectedTags.isNotEmpty() || 
                                selectedYear != "All" || 
                                selectedSeason != "All" || 
                                selectedFormat != "All" || 
                                selectedSort != "All"
        
        if (hasAniListFilters) {
            // Use AniList API with combined search + filters
            android.util.Log.d("GenreFilter", "QuickSearch: Using AniList search with filters, query=$query")
            selectedGenre = null // Clear genre filter on new search
            isShowingAniListResults = true
            currentAniListPage = 1
            
            // Convert UI values to API types
            val seasonYear = if (selectedYear == "All") null else selectedYear?.toIntOrNull()
            val season = if (selectedSeason == "All") null else selectedSeason?.let {
                when (it) {
                    "Winter" -> "WINTER"
                    "Spring" -> "SPRING"
                    "Summer" -> "SUMMER"
                    "Fall" -> "FALL"
                    else -> null
                }
            }
            val format = if (selectedFormat == "All") null else AniListApi.mapFormatToAniListEnum(selectedFormat)
            val sort = if (selectedSort == "All") null else selectedSort?.let {
                when (it) {
                    "Popularity" -> "POPULARITY_DESC"
                    "Average Score" -> "SCORE_DESC"
                    "Trending" -> "TRENDING_DESC"
                    "Favorites" -> "FAVOURITES_DESC"
                    "Title" -> "TITLE_ROMAJI"
                    "Date Added" -> "ID_DESC"
                    "Release Date" -> "START_DATE_DESC"
                    else -> "POPULARITY_DESC"
                }
            }
            
            loadAniListResultsByGenre(selectedGenres.toList(), selectedTags.toList(), 1, seasonYear, season, format, sort, query)
            return true
        }
        
        // Normal provider search (no AniList filters)
        selectedGenre = null // Clear genre filter on new search
        (providers ?: context?.filterProviderByPreferredMedia(hasHomePageIsRequired = false)
            ?.map { it.name }?.toSet())?.let { active ->
            searchViewModel.searchAndCancel(
                query = query,
                ignoreSettings = false,
                providersActive = active,
                isQuickSearch = isQuickSearch
            )
            return true
        }
        return false
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

    private fun showAniListFilterDialog() {
        // Combined list of all AniList genres and tags
        val allGenresAndTags = listOf(
            "Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Horror", "Mahou Shoujo", "Mecha", "Music",
            "Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller",
            "4-koma", "Achromatic", "Achronological Order", "Acrobatics", "Acting", "Adoption", "Advertisement",
            "Afterlife", "Age Gap", "Age Regression", "Agender", "Agriculture", "Airsoft", "Alchemy", "Aliens",
            "Alternate Universe", "American Football", "Amnesia", "Anachronism", "Ancient China", "Angels", "Animals",
            "Anthology", "Anthropomorphism", "Anti-Hero", "Archery", "Aromantic", "Arranged Marriage",
            "Artificial Intelligence", "Asexual", "Assassins", "Astronomy", "Athletics", "Augmented Reality",
            "Autobiographical", "Aviation", "Badminton", "Ballet", "Band", "Bar", "Baseball", "Basketball",
            "Battle Royale", "Biographical", "Bisexual", "Blackmail", "Board Game", "Boarding School",
            "Body Horror", "Body Image", "Body Swapping", "Bowling", "Boxing", "Boys' Love", "Brainwashing",
            "Bullying", "Butler", "Calligraphy", "Camping", "Cannibalism", "Card Battle", "Cars", "Centaur",
            "CGI", "Cheerleading", "Chibi", "Chimera", "Chuunibyou", "Circus", "Class Struggle",
            "Classic Literature", "Classical Music", "Clone", "Coastal", "Cohabitation", "College",
            "Coming of Age", "Conspiracy", "Cosmic Horror", "Cosplay", "Cowboys", "Creature Taming", "Crime",
            "Criminal Organization", "Crossdressing", "Crossover", "Cult", "Cultivation", "Curses",
            "Cute Boys Doing Cute Things", "Cute Girls Doing Cute Things", "Cyberpunk", "Cyborg", "Cycling",
            "Dancing", "Death Game", "Delinquents", "Demons", "Denpa", "Desert", "Detective", "Dinosaurs",
            "Disability", "Dissociative Identities", "Dragons", "Drawing", "Drugs", "Dullahan", "Dungeon",
            "Dystopian", "E-Sports", "Eco-Horror", "Economics", "Educational", "Elderly Protagonist", "Elf",
            "Ensemble Cast", "Environmental", "Episodic", "Ero Guro", "Espionage", "Estranged Family", "Exorcism",
            "Fairy", "Fairy Tale", "Fake Relationship", "Family Life", "Fashion", "Female Harem",
            "Female Protagonist", "Femboy", "Fencing", "Filmmaking", "Firefighters", "Fishing", "Fitness",
            "Flash", "Food", "Football", "Foreign", "Found Family", "Fugitive", "Full CGI", "Full Color",
            "Gambling", "Gangs", "Gekiga", "Gender Bending", "Ghost", "Go", "Goblin", "Gods", "Golf", "Gore",
            "Graduation Project", "Guns", "Gyaru", "Handball", "Henshin", "Heterosexual", "Hikikomori",
            "Hip-hop Music", "Historical", "Homeless", "Horticulture", "Human Experimentation", "Ice Skating",
            "Idol", "Indigenous Cultures", "Inn", "Interspecies", "Isekai", "Iyashikei", "Jazz Music",
            "Josei", "Judo", "Kabuki", "Kaiju", "Karuta", "Kemonomimi", "Kids", "Kingdom Management",
            "Konbini", "Kuudere", "Lacrosse", "Language Barrier", "LGBTQ+ Themes", "Long Strip",
            "Lost Civilization", "Love Triangle", "Mafia", "Magic", "Mahjong", "Maids", "Makeup",
            "Male Harem", "Male Protagonist", "Manzai", "Marriage", "Martial Arts", "Matchmaking",
            "Matriarchy", "Medicine", "Medieval", "Memory Manipulation", "Mermaid", "Meta", "Metal Music",
            "Military", "Mixed Gender Harem", "Mixed Media", "Modeling", "Monster Boy", "Monster Girl",
            "Mopeds", "Motorcycles", "Mountaineering", "Musical Theater", "Mythology", "Natural Disaster",
            "Necromancy", "Nekomimi", "Ninja", "No Dialogue", "Noir", "Non-fiction", "Nudity", "Nun",
            "Office", "Office Lady", "Oiran", "Ojou-sama", "Orphan", "Otaku Culture", "Outdoor Activities",
            "Pandemic", "Parenthood", "Parkour", "Parody", "Philosophy", "Photography", "Pirates", "Poker",
            "Police", "Politics", "Polyamorous", "Post-Apocalyptic", "POV", "Pregnancy", "Primarily Adult Cast",
            "Primarily Animal Cast", "Primarily Child Cast", "Primarily Female Cast", "Primarily Male Cast",
            "Primarily Teen Cast", "Prison", "Proxy Battle", "Psychosexual", "Puppetry", "Rakugo",
            "Real Robot", "Rehabilitation", "Reincarnation", "Religion", "Rescue", "Restaurant", "Revenge",
            "Reverse Isekai", "Robots", "Rock Music", "Rotoscoping", "Royal Affairs", "Rugby", "Rural",
            "Samurai", "Satire", "School", "School Club", "Scuba Diving", "Seinen", "Shapeshifting", "Ships",
            "Shogi", "Shoujo", "Shounen", "Shrine Maiden", "Single-Page Chapter", "Skateboarding", "Skeleton",
            "Slapstick", "Slavery", "Snowscape", "Software Development", "Space", "Space Opera", "Spearplay",
            "Steampunk", "Stop Motion", "Succubus", "Suicide", "Sumo", "Super Power", "Super Robot",
            "Superhero", "Surfing", "Surreal Comedy", "Survival", "Swimming", "Swordplay", "Table Tennis",
            "Tanks", "Tanned Skin", "Teacher", "Teens' Love", "Tennis", "Terrorism", "Time Loop",
            "Time Manipulation", "Time Skip", "Tokusatsu", "Tomboy", "Torture", "Tragedy", "Trains",
            "Transgender", "Travel", "Triads", "Tsundere", "Twins", "Unrequited Love", "Urban", "Urban Fantasy",
            "Vampire", "Vertical Video", "Veterinarian", "Video Games", "Vikings", "Villainess",
            "Virtual World", "Vocal Synth", "Volleyball", "VTuber", "War", "Werewolf", "Wilderness", "Witch",
            "Work", "Wrestling", "Writing", "Wuxia", "Yakuza", "Yandere", "Youkai", "Yuri", "Zombie"
        )

        // UI-only separation: genres (first 18 items) vs tags (rest)
        val genres = allGenresAndTags.take(18)
        val tags = allGenresAndTags.drop(18)
        android.util.Log.d("GenreFilter", "UI separation: allGenresAndTags.size=${allGenresAndTags.size}, genres.size=${genres.size}, tags.size=${tags.size}")

        // Hardcoded Years
        val years = listOf("All") + (1940..2027).reversed().map { it.toString() }

        // Hardcoded Seasons
        val seasons = listOf("All", "Winter", "Spring", "Summer", "Fall")

        // Hardcoded Formats
        val formats = listOf("All", "TV Show", "Movie", "TV Short", "Special", "OVA", "ONA", "Music")

        // Hardcoded Sort Options (display name -> API enum value)
        val sortOptions = listOf("All") + listOf(
            "Popularity" to "POPULARITY_DESC",
            "Average Score" to "SCORE_DESC",
            "Trending" to "TRENDING_DESC",
            "Favorites" to "FAVOURITES_DESC",
            "Title" to "TITLE_ROMAJI",
            "Date Added" to "ID_DESC",
            "Release Date" to "START_DATE_DESC"
        ).map { it.first }

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
            android.util.Log.d("GenreFilter", "Setting up genres adapter with ${genres.size} items")
            val genresAdapter = AniListCheckboxAdapter(genres, dialogGenres, { item, isChecked ->
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
            android.util.Log.d("GenreFilter", "Setting up tags adapter with ${tags.size} items")
            val tagsAdapter = AniListCheckboxAdapter(tags, dialogTags, { item, isChecked ->
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
            val selectedYearsSet = if (dialogYear != null && dialogYear != "All") setOf(dialogYear) else setOf("All")
            var yearsAdapter: AniListCheckboxAdapter? = null
            yearsAdapter = AniListCheckboxAdapter(years, selectedYearsSet, { item, isChecked ->
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
            val selectedSeasonsSet = if (dialogSeason != null && dialogSeason != "All") setOf(dialogSeason) else setOf("All")
            var seasonsAdapter: AniListCheckboxAdapter? = null
            seasonsAdapter = AniListCheckboxAdapter(seasons, selectedSeasonsSet, { item, isChecked ->
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
            val selectedFormatsSet = if (dialogFormat != null && dialogFormat != "All") setOf(dialogFormat) else setOf("All")
            var formatsAdapter: AniListCheckboxAdapter? = null
            formatsAdapter = AniListCheckboxAdapter(formats, selectedFormatsSet, { item, isChecked ->
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
            val selectedSortSet = if (dialogSort != null && dialogSort != "All") setOf(dialogSort) else setOf("All")
            var sortAdapter: AniListCheckboxAdapter? = null
            sortAdapter = AniListCheckboxAdapter(sortOptions, selectedSortSet, { item, isChecked ->
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
                if (dialogBinding.genresRecycler.visibility == View.VISIBLE) {
                    dialogBinding.genresRecycler.visibility = View.GONE
                    dialogBinding.genresExpandIcon.rotation = 0f
                } else {
                    dialogBinding.genresRecycler.visibility = View.VISIBLE
                    dialogBinding.genresExpandIcon.rotation = 90f
                }
            }

            // Accordion toggle for tags
            dialogBinding.tagsHeader.setOnClickListener {
                if (dialogBinding.tagsRecycler.visibility == View.VISIBLE) {
                    dialogBinding.tagsRecycler.visibility = View.GONE
                    dialogBinding.tagsExpandIcon.rotation = 0f
                } else {
                    dialogBinding.tagsRecycler.visibility = View.VISIBLE
                    dialogBinding.tagsExpandIcon.rotation = 90f
                }
            }

            // Accordion toggle for year
            dialogBinding.yearHeader.setOnClickListener {
                if (dialogBinding.yearRecycler.visibility == View.VISIBLE) {
                    dialogBinding.yearRecycler.visibility = View.GONE
                    dialogBinding.yearExpandIcon.rotation = 0f
                } else {
                    dialogBinding.yearRecycler.visibility = View.VISIBLE
                    dialogBinding.yearExpandIcon.rotation = 90f
                }
            }

            // Accordion toggle for season
            dialogBinding.seasonHeader.setOnClickListener {
                if (dialogBinding.seasonRecycler.visibility == View.VISIBLE) {
                    dialogBinding.seasonRecycler.visibility = View.GONE
                    dialogBinding.seasonExpandIcon.rotation = 0f
                } else {
                    dialogBinding.seasonRecycler.visibility = View.VISIBLE
                    dialogBinding.seasonExpandIcon.rotation = 90f
                }
            }

            // Accordion toggle for format
            dialogBinding.formatHeader.setOnClickListener {
                if (dialogBinding.formatRecycler.visibility == View.VISIBLE) {
                    dialogBinding.formatRecycler.visibility = View.GONE
                    dialogBinding.formatExpandIcon.rotation = 0f
                } else {
                    dialogBinding.formatRecycler.visibility = View.VISIBLE
                    dialogBinding.formatExpandIcon.rotation = 90f
                }
            }

            // Accordion toggle for sort
            dialogBinding.sortHeader.setOnClickListener {
                if (dialogBinding.sortRecycler.visibility == View.VISIBLE) {
                    dialogBinding.sortRecycler.visibility = View.GONE
                    dialogBinding.sortExpandIcon.rotation = 0f
                } else {
                    dialogBinding.sortRecycler.visibility = View.VISIBLE
                    dialogBinding.sortExpandIcon.rotation = 90f
                }
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
                sortAdapter?.updateSelectedSet(setOf("All"))
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

                if (dialogGenres.isNotEmpty() || dialogYear != "All" || dialogSeason != "All" || dialogFormat != "All" || dialogSort != "All") {
                    // Update class-level variables
                    selectedGenres.clear()
                    selectedGenres.addAll(dialogGenres)
                    selectedTags.clear()
                    selectedTags.addAll(dialogTags)
                    selectedYear = dialogYear
                    selectedSeason = dialogSeason
                    selectedFormat = dialogFormat
                    selectedSort = dialogSort

                    // Update filter labels
                    updateFilterLabels()
                    updateGenreChips()
                    updateTagsChips()

                    // Convert UI values to API types
                    val seasonYear = if (selectedYear == "All") null else selectedYear?.toIntOrNull()
                    val season = if (selectedSeason == "All") null else selectedSeason?.let {
                        when (it) {
                            "Winter" -> "WINTER"
                            "Spring" -> "SPRING"
                            "Summer" -> "SUMMER"
                            "Fall" -> "FALL"
                            else -> null
                        }
                    }
                    val format = if (selectedFormat == "All") null else AniListApi.mapFormatToAniListEnum(selectedFormat)
                    val sort = if (selectedSort == "All") null else selectedSort?.let {
                        when (it) {
                            "Popularity" -> "POPULARITY_DESC"
                            "Average Score" -> "SCORE_DESC"
                            "Trending" -> "TRENDING_DESC"
                            "Favorites" -> "FAVOURITES_DESC"
                            "Title" -> "TITLE_ROMAJI"
                            "Date Added" -> "ID_DESC"
                            "Release Date" -> "START_DATE_DESC"
                            else -> "POPULARITY_DESC"
                        }
                    }

                    // Load AniList results with new filters
                    isShowingAniListResults = true
                    currentAniListPage = 1
                    loadAniListResultsByGenre(selectedGenres.toList(), selectedTags.toList(), 1, seasonYear, season, format, sort, null)
                } else {
                    // No filters selected - reset to normal mode
                    isShowingAniListResults = false
                    currentAniListPage = 1
                    anilistBrowseResults = emptyList()
                    binding?.quickSearchAutofitResults?.isGone = true
                    binding?.quickSearchMasterRecycler?.isVisible = true
                }
                dialog.dismiss()
            }

            dialog.show()
        }
    }

    private fun updateFilterLabels() {
        binding?.yearLabel?.text = if (selectedYear != null && selectedYear != "All") "Year: $selectedYear" else "Year: All"
        binding?.seasonLabel?.text = if (selectedSeason != null && selectedSeason != "All") "Season: $selectedSeason" else "Season: All"
        binding?.formatLabel?.text = if (selectedFormat != null && selectedFormat != "All") "Format: $selectedFormat" else "Format: All"
        binding?.sortLabel?.text = if (selectedSort != null && selectedSort != "All") "Sort: $selectedSort" else "Sort: Popularity"
    }

    private fun updateGenreChips() {
        binding?.genreChips?.removeAllViews()
        val chipGroup = binding?.genreChips ?: return

        if (selectedGenres.isNotEmpty()) {
            chipGroup.visibility = View.VISIBLE
            selectedGenres.forEach { genre ->
                val chip = Chip(context).apply {
                    text = genre
                    isCloseIconVisible = true
                    setOnCloseIconClickListener {
                        selectedGenres.remove(genre)
                        updateGenreChips()
                        // Reload results without this genre
                        val seasonYear = if (selectedYear == "All") null else selectedYear?.toIntOrNull()
                        val season = if (selectedSeason == "All") null else selectedSeason?.let {
                            when (it) {
                                "Winter" -> "WINTER"
                                "Spring" -> "SPRING"
                                "Summer" -> "SUMMER"
                                "Fall" -> "FALL"
                                else -> null
                            }
                        }
                        val format = if (selectedFormat == "All") null else AniListApi.mapFormatToAniListEnum(selectedFormat)
                        val sort = if (selectedSort == "All") null else selectedSort?.let {
                            when (it) {
                                "Popularity" -> "POPULARITY_DESC"
                                "Average Score" -> "SCORE_DESC"
                                "Trending" -> "TRENDING_DESC"
                                "Favorites" -> "FAVOURITES_DESC"
                                "Title" -> "TITLE_ROMAJI"
                                "Date Added" -> "ID_DESC"
                                "Release Date" -> "START_DATE_DESC"
                                else -> "POPULARITY_DESC"
                            }
                        }
                        currentAniListPage = 1
                        loadAniListResultsByGenre(selectedGenres.toList(), selectedTags.toList(), 1, seasonYear, season, format, sort, null)
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
                val chip = Chip(context).apply {
                    text = tag
                    isCloseIconVisible = true
                    setOnCloseIconClickListener {
                        selectedTags.remove(tag)
                        updateTagsChips()
                        // Reload results without this tag
                        val seasonYear = if (selectedYear == "All") null else selectedYear?.toIntOrNull()
                        val season = if (selectedSeason == "All") null else selectedSeason?.let {
                            when (it) {
                                "Winter" -> "WINTER"
                                "Spring" -> "SPRING"
                                "Summer" -> "SUMMER"
                                "Fall" -> "FALL"
                                else -> null
                            }
                        }
                        val format = if (selectedFormat == "All") null else AniListApi.mapFormatToAniListEnum(selectedFormat)
                        val sort = if (selectedSort == "All") null else selectedSort?.let {
                            when (it) {
                                "Popularity" -> "POPULARITY_DESC"
                                "Average Score" -> "SCORE_DESC"
                                "Trending" -> "TRENDING_DESC"
                                "Favorites" -> "FAVOURITES_DESC"
                                "Title" -> "TITLE_ROMAJI"
                                "Date Added" -> "ID_DESC"
                                "Release Date" -> "START_DATE_DESC"
                                else -> "POPULARITY_DESC"
                            }
                        }
                        currentAniListPage = 1
                        loadAniListResultsByGenre(selectedGenres.toList(), selectedTags.toList(), 1, seasonYear, season, format, sort, null)
                    }
                }
                chipGroup.addView(chip)
            }
        } else {
            chipGroup.visibility = View.GONE
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

    override fun onBindingCreated(binding: QuickSearchBinding) {
        android.util.Log.d("GenreFilter", "QuickSearchFragment: onBindingCreated called")
        android.util.Log.d("GenreFilter", "QuickSearchFragment: arguments=$arguments")
        android.util.Log.d("GenreFilter", "QuickSearchFragment: arguments keys=${arguments?.keySet()}")

        // Filter labels are now static display-only, filter action is via filter icon
        binding.quickGenreFilter.setOnClickListener {
            showAniListFilterDialog()
        }

        arguments?.getStringArray(PROVIDER_KEY)?.let {
            providers = it.toSet()
        }

        val isSingleProvider = providers?.size == 1
        val isSingleProviderQuickSearch = if (isSingleProvider) {
            getApiFromNameNull(providers?.first())?.hasQuickSearch ?: false
        } else false

        val firstProvider = providers?.firstOrNull()
        if (isSingleProvider && firstProvider != null) {
            binding.quickSearchAutofitResults.apply {
                setRecycledViewPool(SearchAdapter.sharedPool)
                adapter = SearchAdapter(
                    this,
                ) { callback ->
                    // Handle AniList browse mode clicks
                    if (isShowingAniListResults) {
                        // Clear AniList filters and redirect to main search with title
                        isShowingAniListResults = false
                        selectedGenres.clear()
                        selectedTags.clear()
                        selectedYear = "All"
                        selectedSeason = "All"
                        selectedFormat = "All"
                        selectedSort = "Popularity"
                        updateFilterLabels()
                        updateGenreChips()
                        updateTagsChips()
                        android.util.Log.d("QuickSearchFragment", "========== AniList result clicked (location 1) ==========")
                        android.util.Log.d("QuickSearchFragment", "Setting nextSearchQuery to: ${callback.card.name}")
                        com.lagradost.cloudstream3.MainActivity.nextSearchQuery = callback.card.name
                        android.util.Log.d("QuickSearchFragment", "Navigating to search tab")
                        activity.navigate(R.id.navigation_search)
                        android.util.Log.d("QuickSearchFragment", "========== Navigation complete ==========")
                    } else {
                        // Normal provider search mode
                        clickCallback?.invoke(callback)
                    }
                }
            }

            binding.quickSearchAutofitResults.addOnScrollListener(object :
                RecyclerView.OnScrollListener() {
                var expandCount = 0

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)

                    val adapter = recyclerView.adapter
                    if (adapter !is SearchAdapter) return

                    val count = adapter.itemCount
                    val currentHasNext = adapter.hasNext

                    if (!recyclerView.isRecyclerScrollable() && currentHasNext && expandCount != count) {
                        expandCount = count
                        ioSafe {
                            searchViewModel.expandAndReturn(firstProvider)
                        }
                    }
                }
            })

            try {
                binding.quickSearch.queryHint =
                    getString(R.string.search_hint_site).format(firstProvider)
            } catch (e: Exception) {
                logError(e)
            }
        } else {
            binding.quickSearchMasterRecycler.setRecycledViewPool(ParentItemAdapter.sharedPool)
            binding.quickSearchMasterRecycler.adapter =
                ParentItemAdapter(
                    id = "quickSearchMasterRecycler".hashCode(),
                    { callback ->
                        SearchHelper.handleSearchClickCallback(callback)
                        //when (callback.action) {
                        //SEARCH_ACTION_LOAD -> {
                        //    clickCallback?.invoke(callback)
                        //}
                        //    else -> SearchHelper.handleSearchClickCallback(activity, callback)
                        //}
                    },
                    { item ->
                        bottomSheetDialog = activity?.loadHomepageList(item, dismissCallback = {
                            bottomSheetDialog = null
                        }, expandCallback = { searchViewModel.expandAndReturn(it) })
                    },
                    expandCallback = { name ->
                        ioSafe {
                            searchViewModel.expandAndReturn(name)
                        }
                    })
            binding.quickSearchMasterRecycler.layoutManager = GridLayoutManager(context, 1)
        }
        binding.quickSearchAutofitResults.isVisible = isSingleProvider
        binding.quickSearchMasterRecycler.isGone = isSingleProvider

        // Filter icon is now always visible (removed anime provider check)

        val listLock = ReentrantLock()
        observe(searchViewModel.currentSearch) { list ->
            try {
                // https://stackoverflow.com/questions/6866238/concurrent-modification-exception-adding-to-an-arraylist
                listLock.lock()
                currentSearchResults = list.toMap()
                (binding.quickSearchMasterRecycler.adapter as? ParentItemAdapter)?.apply {
                    val newItems = list.map { ongoing ->
                        val dataList = ongoing.value.list
                        val dataListFiltered =
                            context?.filterSearchResultByFilmQuality(dataList) ?: dataList

                        val homePageList = HomePageList(
                            ongoing.key,
                            dataListFiltered
                        )

                        val expandableList = HomeViewModel.ExpandableHomepageList(
                            homePageList,
                            ongoing.value.currentPage,
                            ongoing.value.hasNext
                        )

                        expandableList
                    }

                    submitList(newItems)
                    //notifyDataSetChanged()
                }

                // Extract genres from search results
                val allResults = list.values.flatMap { it.list }
                val extractedGenres = allResults.mapNotNull { it.tags }.flatten().distinct().sorted()
                availableGenres = extractedGenres
            } catch (e: Exception) {
                logError(e)
            } finally {
                listLock.unlock()
            }
        }

        val searchExitIcon =
            binding.quickSearch.findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn)

        binding.quickSearch.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                if (search(context, query, false))
                    hideKeyboard(binding.quickSearch)
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                if (isSingleProviderQuickSearch)
                    search(context, newText, true)
                return true
            }
        })
        binding.quickSearchLoadingBar.alpha = 0f
        observe(searchViewModel.searchResponse) {
            when (it) {
                is Resource.Success -> {
                    it.value.let { data ->
                        val adapter =
                            (binding.quickSearchAutofitResults.adapter as? SearchAdapter)
                        adapter?.submitList(
                            context?.filterSearchResultByFilmQuality(data.list) ?: data.list
                        )
                        adapter?.hasNext = data.hasNext
                    }
                    searchExitIcon?.alpha = 1f
                    binding.quickSearchLoadingBar.alpha = 0f
                }

                is Resource.Failure -> {
                    // Toast.makeText(activity, "Server error", Toast.LENGTH_LONG).show()
                    searchExitIcon?.alpha = 1f
                    binding.quickSearchLoadingBar.alpha = 0f
                }

                is Resource.Loading -> {
                    searchExitIcon?.alpha = 0f
                    binding.quickSearchLoadingBar.alpha = 1f
                }
            }
        }

        if (isLayout(PHONE or EMULATOR)) {
            binding.quickSearchBack.apply {
                isVisible = true
                setOnClickListener {
                    activity?.popCurrentPage()
                }
            }
        }

        if (isLayout(TV)) {
            binding.quickSearch.requestFocus()
        }

        android.util.Log.d("GenreFilter", "QuickSearch: Setting up genre filter click listener, button exists=${binding.quickGenreFilter != null}")

        // Set up FAB click listener for load more
        binding.loadMoreFab.setOnClickListener {
            currentAniListPage++

            // Convert UI values to API types
            val seasonYear = if (selectedYear == "All") null else selectedYear?.toIntOrNull()
            val season = if (selectedSeason == "All") null else selectedSeason?.let {
                when (it) {
                    "Winter" -> "WINTER"
                    "Spring" -> "SPRING"
                    "Summer" -> "SUMMER"
                    "Fall" -> "FALL"
                    else -> null
                }
            }
            val format = if (selectedFormat == "All") null else AniListApi.mapFormatToAniListEnum(selectedFormat)
            val sort = if (selectedSort == "All") null else selectedSort?.let {
                when (it) {
                    "Popularity" -> "POPULARITY_DESC"
                    "Average Score" -> "SCORE_DESC"
                    "Trending" -> "TRENDING_DESC"
                    "Favorites" -> "FAVOURITES_DESC"
                    "Title" -> "TITLE_ROMAJI"
                    "Date Added" -> "ID_DESC"
                    "Release Date" -> "START_DATE_DESC"
                    else -> "POPULARITY_DESC"
                }
            }

            loadAniListResultsByGenre(selectedGenres.toList(), selectedTags.toList(), currentAniListPage, seasonYear, season, format, sort, null)
        }

        // Check for AniList filter parameters and auto-load results (after adapter is set up)
        android.util.Log.d("GenreFilter", "QuickSearchFragment: Checking for AniList filter parameters")
        val genres = arguments?.getStringArray(ANILIST_GENRES_KEY)?.toList()
        val tags = arguments?.getStringArray(ANILIST_TAGS_KEY)?.toList()
        val seasonYear = if (arguments?.containsKey(ANILIST_SEASON_YEAR_KEY) == true) arguments?.getInt(ANILIST_SEASON_YEAR_KEY) else null
        val season = arguments?.getString(ANILIST_SEASON_KEY)
        val format = arguments?.getString(ANILIST_FORMAT_KEY)
        val sort = arguments?.getString(ANILIST_SORT_KEY) ?: "POPULARITY_DESC"

        android.util.Log.d("GenreFilter", "QuickSearchFragment: genres=$genres")
        android.util.Log.d("GenreFilter", "QuickSearchFragment: tags=$tags")
        android.util.Log.d("GenreFilter", "QuickSearchFragment: seasonYear=$seasonYear")
        android.util.Log.d("GenreFilter", "QuickSearchFragment: season=$season")
        android.util.Log.d("GenreFilter", "QuickSearchFragment: format=$format")
        android.util.Log.d("GenreFilter", "QuickSearchFragment: sort=$sort")

        if (genres != null || tags != null || seasonYear != null || season != null || format != null || sort != null) {
            android.util.Log.d("GenreFilter", "QuickSearchFragment: AniList filter parameters found, auto-loading results")
            isShowingAniListResults = true
            currentAniListPage = 1
            selectedGenres.addAll(genres ?: emptyList())
            selectedTags.addAll(tags ?: emptyList())
            
            // Convert API parameters back to UI values for filter labels
            selectedYear = seasonYear?.toString() ?: "All"
            selectedSeason = season?.let {
                when (it) {
                    "WINTER" -> "Winter"
                    "SPRING" -> "Spring"
                    "SUMMER" -> "Summer"
                    "FALL" -> "Fall"
                    else -> "All"
                }
            } ?: "All"
            selectedFormat = format?.let {
                when (it) {
                    "TV" -> "TV Show"
                    "TV_SHORT" -> "TV Short"
                    "MOVIE" -> "Movie"
                    "SPECIAL" -> "Special"
                    "OVA" -> "OVA"
                    "ONA" -> "ONA"
                    "MUSIC" -> "Music"
                    else -> "All"
                }
            } ?: "All"
            selectedSort = sort?.let {
                when (it) {
                    "POPULARITY_DESC" -> "Popularity"
                    "SCORE_DESC" -> "Average Score"
                    "TRENDING_DESC" -> "Trending"
                    "FAVOURITES_DESC" -> "Favorites"
                    "TITLE_ROMAJI" -> "Title"
                    "ID_DESC" -> "Date Added"
                    "START_DATE_DESC" -> "Release Date"
                    else -> "Popularity"
                }
            } ?: "Popularity"
            
            // Update filter labels and genre chips
            updateFilterLabels()
            updateGenreChips()
            updateTagsChips()

            // Set up adapter for AniList mode if not already set (needed for multi-provider case)
            if (binding.quickSearchAutofitResults.adapter == null) {
                android.util.Log.d("GenreFilter", "QuickSearchFragment: Setting up adapter for AniList mode")
                binding.quickSearchAutofitResults.apply {
                    setRecycledViewPool(SearchAdapter.sharedPool)
                    adapter = SearchAdapter(
                        this,
                    ) { callback ->
                        // Handle AniList browse mode clicks
                        if (isShowingAniListResults) {
                            // Clear AniList filters and redirect to main search with title
                            isShowingAniListResults = false
                            selectedGenres.clear()
                            selectedTags.clear()
                            selectedYear = "All"
                            selectedSeason = "All"
                            selectedFormat = "All"
                            selectedSort = "Popularity"
                            updateFilterLabels()
                            updateGenreChips()
                            updateTagsChips()
                            android.util.Log.d("QuickSearchFragment", "========== AniList result clicked (location 2) ==========")
                            android.util.Log.d("QuickSearchFragment", "Setting nextSearchQuery to: ${callback.card.name}")
                            com.lagradost.cloudstream3.MainActivity.nextSearchQuery = callback.card.name
                            android.util.Log.d("QuickSearchFragment", "Navigating to search tab")
                            activity.navigate(R.id.navigation_search)
                            android.util.Log.d("QuickSearchFragment", "========== Navigation complete ==========")
                        } else {
                            // Normal provider search mode
                            clickCallback?.invoke(callback)
                        }
                    }
                }
            }

            // Show autofit results and hide master recycler for AniList mode
            binding.quickSearchAutofitResults.isVisible = true
            binding.quickSearchMasterRecycler.isGone = true
            android.util.Log.d("GenreFilter", "QuickSearchFragment: Showing quickSearchAutofitResults for AniList mode")

            android.util.Log.d("GenreFilter", "QuickSearchFragment: Calling loadAniListResultsByGenre with genres=$genres, tags=$tags, seasonYear=$seasonYear, season=$season, format=$format, sort=$sort")
            loadAniListResultsByGenre(genres ?: emptyList(), tags ?: emptyList(), 1, seasonYear, season, format, sort, null)
        } else {
            android.util.Log.d("GenreFilter", "QuickSearchFragment: No AniList filter parameters found")
        }

        arguments?.getString(AUTOSEARCH_KEY)?.let {
            binding.quickSearch.setQuery(it, true)
            arguments?.remove(AUTOSEARCH_KEY)
        }
    }

    private fun filterCurrentResultsByGenre(genre: String?) {
        val list = currentSearchResults ?: return
        val adapter = binding?.quickSearchMasterRecycler?.adapter as? ParentItemAdapter ?: return

        val newItems = list.map { entry ->
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

        adapter.submitList(newItems)
    }

    internal class AniListCheckboxAdapter(
        private val items: List<String>,
        private var selectedItems: Set<String>,
        private val onCheckedChangeListener: (String, Boolean) -> Unit,
        private val radioMode: Boolean = false
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        class CheckboxViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val checkbox: CheckBox = view.findViewById(R.id.checkbox)
            val text: TextView = view.findViewById(R.id.text)
        }

        class RadioViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(R.id.text)
            val tickMark: ImageView = view.findViewById(R.id.tick_mark)
            val selectionHighlight: View = view.findViewById(R.id.selection_highlight)
        }

        fun updateSelectedSet(newSet: Set<String>) {
            selectedItems = newSet
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            return if (radioMode) 1 else 0
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 1) {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_anilist_radio_selection, parent, false)
                RadioViewHolder(view)
            } else {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_anilist_checkbox, parent, false)
                CheckboxViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            val isSelected = selectedItems.contains(item)

            if (holder is CheckboxViewHolder) {
                holder.text.text = item
                holder.checkbox.isChecked = isSelected

                holder.itemView.setOnClickListener {
                    holder.checkbox.isChecked = !holder.checkbox.isChecked
                }

                holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                    onCheckedChangeListener(item, isChecked)
                }
            } else if (holder is RadioViewHolder) {
                holder.text.text = item
                holder.tickMark.visibility = if (isSelected) View.VISIBLE else View.GONE
                if (isSelected) {
                    holder.selectionHighlight.setBackgroundColor(android.graphics.Color.parseColor("#1A000000"))
                    holder.text.alpha = 1.0f
                } else {
                    holder.selectionHighlight.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    holder.text.alpha = 1.0f
                }

                holder.itemView.setOnClickListener {
                    onCheckedChangeListener(item, true)
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
