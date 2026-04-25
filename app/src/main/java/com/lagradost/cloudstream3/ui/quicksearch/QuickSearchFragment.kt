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
import androidx.navigation.fragment.findNavController
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
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import androidx.recyclerview.widget.StaggeredGridLayoutManager

class QuickSearchFragment : BaseFragment<QuickSearchBinding>(
    BaseFragment.BindingCreator.Inflate(QuickSearchBinding::inflate)
) {
    // ... rest of the code remains the same ...
    companion object {
        const val AUTOSEARCH_KEY = "autosearch"
        const val PROVIDER_KEY = "providers"

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

        var clickCallback: ((SearchClickCallback) -> Unit)? = null
    }

    private var providers: Set<String>? = null
    private lateinit var searchViewModel: SearchViewModel

    private var bottomSheetDialog: BottomSheetDialog? = null

    var availableGenres = listOf<String>()
    var selectedGenre: String? = null
    var anilistGenreMap: Map<String, List<String>> = emptyMap()
    var currentSearchResults: Map<String, com.lagradost.cloudstream3.ui.search.ExpandableSearchList>? = null

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

    override fun onBindingCreated(binding: QuickSearchBinding) {
        android.util.Log.d("GenreFilter", "QuickSearchFragment: onBindingCreated called")
        android.util.Log.d("GenreFilter", "QuickSearchFragment: arguments=$arguments")
        android.util.Log.d("GenreFilter", "QuickSearchFragment: arguments keys=${arguments?.keySet()}")

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
                    clickCallback?.invoke(callback)
                }
            }

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

                        // Show toast when no more results
                        if (!data.hasNext && data.list.isNotEmpty()) {
                            showNoMoreResultsToast()
                        }
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

    private fun showNoMoreResultsToast() {
        binding?.noMoreResultsToast?.apply {
            visibility = View.VISIBLE
            alpha = 0f
            animate()
                .alpha(1f)
                .setDuration(300)
                .withEndAction {
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
                .start()
        }
    }
}
