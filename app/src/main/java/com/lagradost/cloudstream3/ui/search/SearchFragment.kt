package com.lagradost.cloudstream3.ui.search

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.DialogInterface
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.core.view.doOnLayout
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
import com.lagradost.cloudstream3.syncproviders.SyncRepo
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
import androidx.navigation.fragment.findNavController
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

    // Class-level variable to hold the current search query
    var sq: String? = null

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
        android.util.Log.d("GENRE_FILTER_REDIRECT", "========== SearchFragment.onResume called ==========")
        android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: MainActivity.nextSearchQuery = ${MainActivity.nextSearchQuery}")
        android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: sq = $sq")
        android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: mainSearch.query = ${binding?.mainSearch?.query}")
        android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: mainSearch.isIconified = ${binding?.mainSearch?.isIconified}")
        android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: mainSearch.hasFocus = ${binding?.mainSearch?.hasFocus()}")
        android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: mainSearch.isFocused = ${binding?.mainSearch?.isFocused}")
        
        afterPluginsLoadedEvent += ::reloadRepos
        
        android.util.Log.d("NAV_ARGS_FIX", "========== SearchFragment.onResume: Checking for search query ==========")
        android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: Checking navigation arguments first")
        val navQuery = arguments?.getString("search_query")
        android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: Navigation argument search_query = '$navQuery'")
        android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: Static variable MainActivity.nextSearchQuery = ${MainActivity.nextSearchQuery}")
        
        // Check for search query from navigation arguments first (preferred method)
        // This handles navigation via NavController with arguments
        if (navQuery != null && navQuery.isNotBlank()) {
            android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: Using navigation argument query: '$navQuery'")
            android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: query length = ${navQuery.length}")
            android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: query isBlank = ${navQuery.isBlank()}")
            android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: setting sq and triggering search with query from nav args: '$navQuery'")
            // Update the class-level sq variable
            sq = navQuery
            android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: sq updated to: '$sq'")
            
            // Clear the argument to prevent re-triggering on resume
            arguments?.remove("search_query")
            android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: Cleared navigation argument to prevent re-trigger")
            
            // Force clear and set the search bar text completely
            binding?.mainSearch?.let { searchView ->
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: before clear - searchView.query = '${searchView.query}'")
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: before clear - searchView.hasFocus = ${searchView.hasFocus()}")
                searchView.setQuery("", false)
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: after setQuery('') - searchView.query = '${searchView.query}'")
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: after setQuery('') - searchView.hasFocus = ${searchView.hasFocus()}")
                
                // Clear the EditText directly
                val editText = searchView.findViewById<android.widget.EditText>(androidx.appcompat.R.id.search_src_text)
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: editText.text before clear = '${editText?.text}'")
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: editText.hasFocus before clear = ${editText?.hasFocus()}")
                editText?.text?.clear()
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: editText.text after clear = '${editText?.text}'")
                editText?.text?.append(navQuery)
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: editText.text after append = '${editText?.text}'")
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: editText.hasFocus after append = ${editText?.hasFocus()}")
                
                searchView.setQuery(navQuery, true)
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: after setQuery('$navQuery', true) - searchView.query = '${searchView.query}'")
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: after setQuery('$navQuery', true) - searchView.hasFocus = ${searchView.hasFocus()}")
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: after setQuery('$navQuery', true) - editText.hasFocus = ${editText?.hasFocus()}")
                
                // FIX: Clear focus from searchView to prevent keyboard from showing and autocomplete from triggering
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: ATTEMPTING FIX - clearing focus from searchView")
                searchView.clearFocus()
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: after clearFocus() - searchView.hasFocus = ${searchView.hasFocus()}")
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: after clearFocus() - editText.hasFocus = ${editText?.hasFocus()}")
                
                // Also hide keyboard explicitly
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: hiding keyboard")
                hideKeyboard(searchView)
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: keyboard hidden")
            }
            
            // Trigger the search
            android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: calling search('$navQuery')")
            search(navQuery)
            android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: Search triggered with navigation argument query")
            // Clear suggestions to hide overlay when returning from entry
            searchViewModel.clearSuggestions()
            android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: cleared suggestions")
        } 
        // Check for search query from MainActivity.nextSearchQuery when resuming (fallback)
        // This handles tab switching via bottom navigation
        else if (MainActivity.nextSearchQuery != null) {
            val query = MainActivity.nextSearchQuery
            android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: No navigation argument, using static variable fallback")
            android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: MainActivity.nextSearchQuery is not null, query: '$query'")
            android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: query length = ${query?.length}")
            android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: query isBlank = ${query?.isBlank()}")
            if (query?.isNotBlank() == true) {
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: setting sq and triggering search with query: '$query'")
                // Update the class-level sq variable
                sq = query
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: sq updated to: '$sq'")
                
                // Force clear and set the search bar text completely
                binding?.mainSearch?.let { searchView ->
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: before clear - searchView.query = '${searchView.query}'")
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: before clear - searchView.hasFocus = ${searchView.hasFocus()}")
                    searchView.setQuery("", false)
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: after setQuery('') - searchView.query = '${searchView.query}'")
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: after setQuery('') - searchView.hasFocus = ${searchView.hasFocus()}")
                    
                    // Clear the EditText directly
                    val editText = searchView.findViewById<android.widget.EditText>(androidx.appcompat.R.id.search_src_text)
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: editText.text before clear = '${editText?.text}'")
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: editText.hasFocus before clear = ${editText?.hasFocus()}")
                    editText?.text?.clear()
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: editText.text after clear = '${editText?.text}'")
                    editText?.text?.append(query)
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: editText.text after append = '${editText?.text}'")
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: editText.hasFocus after append = ${editText?.hasFocus()}")
                    
                    searchView.setQuery(query, true)
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: after setQuery('$query', true) - searchView.query = '${searchView.query}'")
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: after setQuery('$query', true) - searchView.hasFocus = ${searchView.hasFocus()}")
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: after setQuery('$query', true) - editText.hasFocus = ${editText?.hasFocus()}")
                    
                    // FIX: Clear focus from searchView to prevent keyboard from showing and autocomplete from triggering
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: ATTEMPTING FIX - clearing focus from searchView")
                    searchView.clearFocus()
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: after clearFocus() - searchView.hasFocus = ${searchView.hasFocus()}")
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: after clearFocus() - editText.hasFocus = ${editText?.hasFocus()}")
                    
                    // Also hide keyboard explicitly
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: hiding keyboard")
                    hideKeyboard(searchView)
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: keyboard hidden")
                }
                
                // Trigger the search
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: calling search('$query')")
                search(query)
                MainActivity.nextSearchQuery = null
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: cleared nextSearchQuery after triggering search")
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: MainActivity.nextSearchQuery is now ${MainActivity.nextSearchQuery}")
                // Clear suggestions to hide overlay when returning from entry
                searchViewModel.clearSuggestions()
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: cleared suggestions")
            } else {
                // Clear nextSearchQuery even if we don't use it to prevent future redirects
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: clearing nextSearchQuery without triggering search (query is blank)")
                MainActivity.nextSearchQuery = null
            }
        } else {
            android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: MainActivity.nextSearchQuery is null, nothing to do")
        }
        
        // Clear suggestions when returning from entry (back navigation)
        // This handles the case where nextSearchQuery is null but search bar has text
        if (binding?.mainSearch?.query?.isNotBlank() == true) {
            android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: search bar has text, clearing suggestions for back navigation")
            searchViewModel.clearSuggestions()
        }
        
        android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: final state - sq = '$sq', mainSearch.query = '${binding?.mainSearch?.query}'")
        android.util.Log.d("GENRE_FILTER_REDIRECT", "onResume: final state - mainSearch.hasFocus = ${binding?.mainSearch?.hasFocus()}")
        android.util.Log.d("GENRE_FILTER_REDIRECT", "========== SearchFragment.onResume completed ==========")
    }

    override fun onStart() {
        super.onStart()
        android.util.Log.d("GENRE_FILTER_REDIRECT", "========== SearchFragment.onStart called ==========")
        android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: onStart: Checking navigation arguments first")
        val navQuery = arguments?.getString("search_query")
        android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: onStart: Navigation argument search_query = '$navQuery'")
        android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: onStart: Static variable MainActivity.nextSearchQuery = ${MainActivity.nextSearchQuery}")
        android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: mainSearch query: ${binding?.mainSearch?.query}")
        android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: sq = $sq")
        
        // Force the SearchView to use the current sq value (from onBindingCreated)
        // This prevents SearchView state restoration from reverting to an old query
        if (sq != null && sq != binding?.mainSearch?.query) {
            android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: forcing SearchView to use sq: $sq (current query: ${binding?.mainSearch?.query})")
            binding?.mainSearch?.setQuery(sq, false)
        }
        
        android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: onStart: Checking for search query from navigation arguments first")
        // Check for search query from navigation arguments first (preferred method)
        if (navQuery != null && navQuery.isNotBlank()) {
            android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: onStart: Using navigation argument query: '$navQuery'")
            android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: onStart: query length = ${navQuery.length}")
            android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: onStart: query isBlank = ${navQuery.isBlank()}")
            android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: setting sq and triggering search with query from nav args: '$navQuery'")
            // Update the class-level sq variable
            sq = navQuery
            android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: onStart: sq updated to: '$sq'")
            
            // Clear the argument to prevent re-triggering
            arguments?.remove("search_query")
            android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: onStart: Cleared navigation argument to prevent re-trigger")
            
            // Force clear and set the search bar text completely
            binding?.mainSearch?.let { searchView ->
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: before clear - searchView.query = '${searchView.query}'")
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: before clear - searchView.hasFocus = ${searchView.hasFocus()}")
                searchView.setQuery("", false)
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: after setQuery('') - searchView.query = '${searchView.query}'")
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: after setQuery('') - searchView.hasFocus = ${searchView.hasFocus()}")
                
                // Clear the EditText directly
                val editText = searchView.findViewById<android.widget.EditText>(androidx.appcompat.R.id.search_src_text)
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: editText.text before clear = '${editText?.text}'")
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: editText.hasFocus before clear = ${editText?.hasFocus()}")
                editText?.text?.clear()
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: editText.text after clear = '${editText?.text}'")
                editText?.text?.append(navQuery)
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: editText.text after append = '${editText?.text}'")
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: editText.hasFocus after append = ${editText?.hasFocus()}")
                
                searchView.setQuery(navQuery, true)
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: after setQuery('$navQuery', true) - searchView.query = '${searchView.query}'")
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: after setQuery('$navQuery', true) - searchView.hasFocus = ${searchView.hasFocus()}")
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: after setQuery('$navQuery', true) - editText.hasFocus = ${editText?.hasFocus()}")
                
                // FIX: Clear focus from searchView to prevent keyboard from showing and autocomplete from triggering
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: ATTEMPTING FIX - clearing focus from searchView")
                searchView.clearFocus()
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: after clearFocus() - searchView.hasFocus = ${searchView.hasFocus()}")
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: after clearFocus() - editText.hasFocus = ${editText?.hasFocus()}")
                
                // Also hide keyboard explicitly
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: hiding keyboard")
                hideKeyboard(searchView)
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: keyboard hidden")
            }
            
            // Trigger the search
            android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: calling search('$navQuery')")
            search(navQuery)
            android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: onStart: Search triggered with navigation argument query")
            // Clear suggestions to hide overlay when returning from entry
            searchViewModel.clearSuggestions()
            android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: cleared suggestions")
        }
        // Check for search query from MainActivity.nextSearchQuery when fragment starts (fallback)
        // This handles tab switching via bottom navigation when fragment is already in backstack
        else if (MainActivity.nextSearchQuery != null) {
            val query = MainActivity.nextSearchQuery
            android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: onStart: No navigation argument, using static variable fallback")
            android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: MainActivity.nextSearchQuery is not null, query: '$query'")
            android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: query length = ${query?.length}")
            android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: query isBlank = ${query?.isBlank()}")
            if (query?.isNotBlank() == true) {
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: setting sq and triggering search with query: '$query'")
                // Update the class-level sq variable
                sq = query
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: sq updated to: '$sq'")
                
                // Force clear and set the search bar text completely
                binding?.mainSearch?.let { searchView ->
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: before clear - searchView.query = '${searchView.query}'")
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: before clear - searchView.hasFocus = ${searchView.hasFocus()}")
                    searchView.setQuery("", false)
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: after setQuery('') - searchView.query = '${searchView.query}'")
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: after setQuery('') - searchView.hasFocus = ${searchView.hasFocus()}")
                    
                    // Clear the EditText directly
                    val editText = searchView.findViewById<android.widget.EditText>(androidx.appcompat.R.id.search_src_text)
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: editText.text before clear = '${editText?.text}'")
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: editText.hasFocus before clear = ${editText?.hasFocus()}")
                    editText?.text?.clear()
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: editText.text after clear = '${editText?.text}'")
                    editText?.text?.append(query)
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: editText.text after append = '${editText?.text}'")
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: editText.hasFocus after append = ${editText?.hasFocus()}")
                    
                    searchView.setQuery(query, true)
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: after setQuery('$query', true) - searchView.query = '${searchView.query}'")
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: after setQuery('$query', true) - searchView.hasFocus = ${searchView.hasFocus()}")
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: after setQuery('$query', true) - editText.hasFocus = ${editText?.hasFocus()}")
                    
                    // FIX: Clear focus from searchView to prevent keyboard from showing and autocomplete from triggering
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: ATTEMPTING FIX - clearing focus from searchView")
                    searchView.clearFocus()
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: after clearFocus() - searchView.hasFocus = ${searchView.hasFocus()}")
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: after clearFocus() - editText.hasFocus = ${editText?.hasFocus()}")
                    
                    // Also hide keyboard explicitly
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: hiding keyboard")
                    hideKeyboard(searchView)
                    android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: keyboard hidden")
                }
                
                // Trigger the search
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: calling search('$query')")
                search(query)
                MainActivity.nextSearchQuery = null
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: cleared nextSearchQuery after triggering search")
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: MainActivity.nextSearchQuery is now ${MainActivity.nextSearchQuery}")
                // Clear suggestions to hide overlay when returning from entry
                searchViewModel.clearSuggestions()
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: cleared suggestions")
            } else {
                // Clear nextSearchQuery even if we don't use it to prevent future redirects
                android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: clearing nextSearchQuery without triggering search (query is blank)")
                MainActivity.nextSearchQuery = null
            }
        } else {
            android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: MainActivity.nextSearchQuery is null, nothing to do")
        }
        
        // Check for search query from navigation arguments (from BrowseFragment)
        // This takes priority over nextSearchQuery
        val bundleQuery = arguments?.getString("search_query")
        if (bundleQuery != null && bundleQuery.isNotBlank()) {
            android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: got query from bundle: $bundleQuery")
            // Clear the argument to prevent it from being used again
            arguments?.remove("search_query")
            
            // Force clear and set the search bar text
            binding?.mainSearch?.let { searchView ->
                searchView.setQuery("", false)
                // Clear the EditText directly
                val editText = searchView.findViewById<android.widget.EditText>(androidx.appcompat.R.id.search_src_text)
                editText?.text?.clear()
                editText?.text?.append(bundleQuery)
                searchView.setQuery(bundleQuery, true)
            }
            search(bundleQuery)
            android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: triggered search from bundle query")
        }
        android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: final state - sq = '$sq', mainSearch.query = '${binding?.mainSearch?.query}'")
        android.util.Log.d("GENRE_FILTER_REDIRECT", "onStart: final state - mainSearch.hasFocus = ${binding?.mainSearch?.hasFocus()}")
        android.util.Log.d("GENRE_FILTER_REDIRECT", "========== SearchFragment.onStart completed ==========")
    }

    override fun onStop() {
        super.onStop()
        android.util.Log.d("GENRE_FILTER_REDIRECT", "========== SearchFragment.onStop called ==========")
        android.util.Log.d("GENRE_FILTER_REDIRECT", "onStop: MainActivity.nextSearchQuery = ${MainActivity.nextSearchQuery}")
        android.util.Log.d("GENRE_FILTER_REDIRECT", "onStop: sq = $sq")
        android.util.Log.d("GENRE_FILTER_REDIRECT", "onStop: mainSearch.query = ${binding?.mainSearch?.query}")
        afterPluginsLoadedEvent -= ::reloadRepos
    }

    var selectedSearchTypes = mutableListOf<TvType>()
    var selectedApis = mutableSetOf<String>()
    var availableGenres = listOf<String>()
    var selectedGenre: String? = null
    var currentSearchResults: Map<String, com.lagradost.cloudstream3.ui.search.ExpandableSearchList>? = null

    fun search(query: String?) {
        android.util.Log.d("SearchFragment", "search() called with query: $query")
        android.util.Log.d("SearchFragment", "search() call stack:", Exception())
        if (query == null) {
            android.util.Log.d("SearchFragment", "search() returned early because query is null")
            return
        }
        if (query.isBlank()) {
            android.util.Log.d("SearchFragment", "search() returned early because query is blank")
            return
        }
        selectedGenre = null // Clear genre filter on new search
        // Clear all adapters to prevent result pollution
        (binding?.searchMasterRecycler?.adapter as? BaseAdapter<*, *>)?.clearState()
        (binding?.searchAutofitResults?.adapter as? BaseAdapter<*, *>)?.clearState()
        context?.let { ctx ->
            val default = enumValues<TvType>().sorted().filter { it != TvType.NSFW }
                .map { it.ordinal.toString() }.toSet()
            val preferredTypes = (PreferenceManager.getDefaultSharedPreferences(ctx)
                .getStringSet(this.getString(R.string.prefer_media_type_key), default)
                ?.ifEmpty { default } ?: default)
                .mapNotNull { it.toIntOrNull() ?: return@mapNotNull null }

            val settings = ctx.getApiSettings()

            android.util.Log.d("SearchFragment", "selectedApis: $selectedApis")
            android.util.Log.d("SearchFragment", "settings: $settings")
            android.util.Log.d("SearchFragment", "preferredTypes: $preferredTypes")
            android.util.Log.d("SearchFragment", "selectedSearchTypes: $selectedSearchTypes")

            val notFilteredBySelectedTypes = selectedApis.filter { name ->
                settings.contains(name)
            }.map { name ->
                name to getApiFromNameNull(name)?.supportedTypes
            }.filter { (_, types) ->
                types?.any { preferredTypes.contains(it.ordinal) } == true
            }

            android.util.Log.d("SearchFragment", "notFilteredBySelectedTypes: $notFilteredBySelectedTypes")

            val providersActive = notFilteredBySelectedTypes.filter { (_, types) ->
                types?.any { selectedSearchTypes.contains(it) } == true
            }.ifEmpty { notFilteredBySelectedTypes }.map { it.first }.toSet()

            android.util.Log.d("SearchFragment", "providersActive: $providersActive")

            android.util.Log.d("SearchFragment", "About to call searchViewModel.searchAndCancel, searchViewModel: $searchViewModel")
            try {
                searchViewModel.searchAndCancel(
                    query = query,
                    providersActive = providersActive
                )
                android.util.Log.d("SearchFragment", "Successfully called searchViewModel.searchAndCancel")
            } catch (e: Exception) {
                android.util.Log.e("SearchFragment", "Exception calling searchViewModel.searchAndCancel", e)
            }
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
                item.tags?.contains(genre) == true
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
                android.util.Log.d("SearchFragment", "reloadRepos chip callback: selectedSearchTypes changed from $selectedSearchTypes to $list")
                if (selectedSearchTypes.toSet() != list.toSet()) {
                    DataStoreHelper.searchPreferenceTags = list
                    selectedSearchTypes.clear()
                    selectedSearchTypes.addAll(list)
                    val currentQuery = binding?.mainSearch?.query?.toString()
                    android.util.Log.d("SearchFragment", "reloadRepos: currentQuery: $currentQuery")
                    if (!currentQuery.isNullOrBlank()) {
                        android.util.Log.d("SearchFragment", "reloadRepos: calling search with query: $currentQuery")
                        search(currentQuery)
                    } else {
                        android.util.Log.d("SearchFragment", "reloadRepos: query is blank, not calling search")
                    }
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
        android.util.Log.d("SearchFragment", "onBindingCreated called")
        
        binding.apply {
            android.util.Log.d("SearchFragment", "onBindingCreated: inside binding.apply")
            val adapter =
                SearchAdapter(
                    searchAutofitResults,
                ) { callback ->
                    // Normal provider search handling
                    SearchHelper.handleSearchClickCallback(callback)
                }

            searchRoot.findViewById<TextView>(androidx.appcompat.R.id.search_src_text)?.tag =
                "tv_no_focus_tag"
            android.util.Log.d("SearchFragment", "onBindingCreated: after searchRoot setup")
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

        android.util.Log.d("SearchFragment", "onBindingCreated: after voiceSearch setup")
        val searchExitIcon =
            binding.mainSearch.findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn)

        // Enable submit button for SearchView to handle search icon clicks
        binding.mainSearch.isSubmitButtonEnabled = true

        selectedApis = DataStoreHelper.searchPreferenceProviders.toMutableSet()

        android.util.Log.d("SearchFragment", "onBindingCreated: after selectedApis setup")
        try {
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
        }
        catch (e: Exception) {
            android.util.Log.e("SearchFragment", "Exception in searchFilter setup", e)
        }

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
                android.util.Log.d("SearchFragment", "onQueryTextSubmit called with query: $query")
                android.util.Log.d("SearchFragment", "onQueryTextSubmit: current sq = $sq")
                android.util.Log.d("SearchFragment", "onQueryTextSubmit: mainSearch.query = ${binding.mainSearch.query}")
                search(query)
                searchViewModel.clearSuggestions()

                binding.mainSearch.let {
                    hideKeyboard(it)
                }

                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                android.util.Log.d("SearchFragment", "onQueryTextChange called with newText: $newText")
                android.util.Log.d("SearchFragment", "onQueryTextChange: current sq = $sq")
                android.util.Log.d("SearchFragment", "onQueryTextChange: mainSearch.query = ${binding.mainSearch.query}")
                val showHistory = newText?.isBlank() ?: true
                if (showHistory) {
                    searchViewModel.clearSearch()
                    searchViewModel.updateHistory()
                    searchViewModel.clearSuggestions()
                } else {
                    // Fetch suggestions when user is typing (if enabled)
                    if (isSearchSuggestionsEnabled) {
                        searchViewModel.fetchSuggestions(newText ?: "")
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

        android.util.Log.d("SearchFragment", "onBindingCreated: after searchFilter setup")
        android.util.Log.d("SearchFragment", "About to call observe(searchViewModel.searchResponse)")
        observe(searchViewModel.searchResponse) {
            android.util.Log.d("SearchFragment", "searchResponse observer received: $it")
            when (it) {
                is Resource.Success -> {
                    android.util.Log.d("SearchFragment", "searchResponse is Success, value: ${it.value}")
                    it.value.let { data ->
                        val list = data.list
                        android.util.Log.d("SearchFragment", "searchResponse list size: ${list.size}, isNotEmpty: ${list.isNotEmpty()}")
                        if (list.isNotEmpty()) {
                            android.util.Log.d("SearchFragment", "Submitting list to SearchAdapter")
                            (binding.searchAutofitResults.adapter as? SearchAdapter)?.submitList(
                                list
                            )
                        } else {
                            android.util.Log.d("SearchFragment", "searchResponse list is empty, not submitting")
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
            sq = arguments?.getString(SEARCH_QUERY) ?: savedInstanceState?.getString(SEARCH_QUERY)
            if (sq.isNullOrBlank()) {
                sq = MainActivity.nextSearchQuery
            }

            sq?.let { query ->
                if (query.isBlank()) return@let

                // Queries are dropped if you are submitted before layout finishes
                mainSearch.doOnLayout {
                    mainSearch.setQuery(query, true)
                }
                // Clear the query as to not make it request the same query every time the page is opened
                arguments?.remove(SEARCH_QUERY)
                savedInstanceState?.remove(SEARCH_QUERY)
                MainActivity.nextSearchQuery = null
            }
        }

        // Call reloadRepos after query initialization to ensure query is set before chip binding
        reloadRepos()

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

        searchViewModel.updateHistory()
    }
}
