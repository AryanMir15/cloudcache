package com.lagradost.cloudstream3.ui.home

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.navigation.NavigationBarItemView
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.databinding.FragmentHomeHeadBinding
import com.lagradost.cloudstream3.databinding.FragmentHomeHeadTvBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.debugException
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.account.AccountHelper.showAccountEditDialog
import com.lagradost.cloudstream3.ui.account.AccountHelper.showAccountSelectLinear
import com.lagradost.cloudstream3.ui.account.AccountViewModel
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.ResultFragment.bindLogo
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.ui.result.START_ACTION_RESUME_LATEST
import com.lagradost.cloudstream3.ui.result.getId
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_SHOW_METADATA
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.databinding.BottomAnilistGenreTagSelectorBinding
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialog
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showOptionSelectStringRes
import com.lagradost.cloudstream3.utils.UIHelper.fixPaddingStatusbarMargin
import com.lagradost.cloudstream3.utils.UIHelper.fixPaddingStatusbarView
import com.lagradost.cloudstream3.utils.UIHelper.populateChips
import androidx.core.graphics.toColorInt
import com.lagradost.cloudstream3.ui.setRecycledViewPool

class HomeParentItemAdapterPreview(
    val fragment: LifecycleOwner,
    private val viewModel: HomeViewModel,
    private val accountViewModel: AccountViewModel
) : ParentItemAdapter(
    id = "HomeParentItemAdapterPreview".hashCode(),
    clickCallback = {
        viewModel.click(it)
    }, moreInfoClickCallback = {
        viewModel.popup(it)
    }, expandCallback = {
        viewModel.expand(it)
    }) {
    override val headers = 1
    override fun onCreateHeader(parent: ViewGroup): ViewHolderState<Bundle> {
        val inflater = LayoutInflater.from(parent.context)
        val binding = if (isLayout(TV or EMULATOR)) FragmentHomeHeadTvBinding.inflate(
            inflater,
            parent,
            false
        ) else FragmentHomeHeadBinding.inflate(inflater, parent, false)

        if (binding is FragmentHomeHeadTvBinding && isLayout(EMULATOR)) {
            binding.homeBookmarkParentItemMoreInfo.isVisible = true

            val marginInDp = 50
            val density = binding.horizontalScrollChips.context.resources.displayMetrics.density
            val marginInPixels = (marginInDp * density).toInt()

            val params = binding.horizontalScrollChips.layoutParams as ViewGroup.MarginLayoutParams
            params.marginEnd = marginInPixels
            binding.horizontalScrollChips.layoutParams = params
            binding.homeWatchParentItemTitle.setCompoundDrawablesWithIntrinsicBounds(
                null,
                null,
                ContextCompat.getDrawable(
                    parent.context,
                    R.drawable.ic_baseline_arrow_forward_24
                ),
                null
            )
        }

        return HeaderViewHolder(binding, viewModel, accountViewModel, fragment)
    }

    override fun onBindHeader(holder: ViewHolderState<Bundle>) {
        (holder as? HeaderViewHolder)?.bind()
    }

    override fun onViewDetachedFromWindow(holder: ViewHolderState<Bundle>) {
        when (holder) {
            is HeaderViewHolder -> {
                holder.onViewDetachedFromWindow()
            }
        }
    }

    override fun onViewAttachedToWindow(holder: ViewHolderState<Bundle>) {
        when (holder) {
            is HeaderViewHolder -> {
                holder.onViewAttachedToWindow()
            }
        }
    }

    private class HeaderViewHolder(
        val binding: ViewBinding,
        val viewModel: HomeViewModel,
        accountViewModel: AccountViewModel,
        fragment: LifecycleOwner,
    ) :
        ViewHolderState<Bundle>(binding) {

        override fun save(): Bundle =
            Bundle().apply {
                putParcelable(
                    "resumeRecyclerView",
                    resumeRecyclerView.layoutManager?.onSaveInstanceState()
                )
                putParcelable(
                    "bookmarkRecyclerView",
                    bookmarkRecyclerView.layoutManager?.onSaveInstanceState()
                )
                //putInt("previewViewpager", previewViewpager.currentItem)
            }

        override fun restore(state: Bundle) {
            state.getSafeParcelable<Parcelable>("resumeRecyclerView")?.let { recycle ->
                resumeRecyclerView.layoutManager?.onRestoreInstanceState(recycle)
            }
            state.getSafeParcelable<Parcelable>("bookmarkRecyclerView")?.let { recycle ->
                bookmarkRecyclerView.layoutManager?.onRestoreInstanceState(recycle)
            }
        }

        val previewAdapter = HomeScrollAdapter { view, position, item ->
            viewModel.click(
                LoadClickCallback(0, view, position, item)
            )
        }

        private val resumeAdapter = ResumeItemAdapter(
            nextFocusUp = itemView.nextFocusUpId,
            nextFocusDown = itemView.nextFocusDownId,
            removeCallback = { v ->
                try {
                    val context = v.context ?: return@ResumeItemAdapter
                    val builder: AlertDialog.Builder =
                        AlertDialog.Builder(context)
                    // Copy pasted from https://github.com/recloudstream/cloudstream/pull/1658/files
                    builder.apply {
                        setTitle(R.string.clear_history)
                        setMessage(
                            context.getString(R.string.delete_message).format(
                                context.getString(
                                    R.string.continue_watching
                                )
                            )
                        )
                        setNegativeButton(R.string.cancel) { _, _ -> /*NO-OP*/ }
                        setPositiveButton(R.string.delete) { _, _ ->
                            DataStoreHelper.deleteAllResumeStateIds()
                            viewModel.reloadStored()
                        }
                        show().setDefaultFocus()
                    }
                } catch (t: Throwable) {
                    // This may throw a formatting error
                    logError(t)
                }
            },
            clickCallback = { callback ->
                if (callback.action != SEARCH_ACTION_SHOW_METADATA) {
                    viewModel.click(callback)
                    return@ResumeItemAdapter
                }
                callback.view.context?.getActivity()?.showOptionSelectStringRes(
                    callback.view,
                    callback.card.posterUrl,
                    listOf(
                        R.string.action_open_watching,
                        R.string.action_remove_watching
                    ),
                    listOf(
                        R.string.action_open_play,
                        R.string.action_open_watching,
                        R.string.action_remove_watching
                    )
                ) { (isTv, actionId) ->
                    when (actionId + if (isTv) 0 else 1) {
                        // play
                        0 -> {
                            viewModel.click(
                                SearchClickCallback(
                                    START_ACTION_RESUME_LATEST,
                                    callback.view,
                                    -1,
                                    callback.card
                                )
                            )
                        }
                        //info
                        1 -> {
                            viewModel.click(
                                SearchClickCallback(
                                    SEARCH_ACTION_LOAD,
                                    callback.view,
                                    -1,
                                    callback.card
                                )
                            )
                        }
                        // remove
                        2 -> {
                            val card = callback.card
                            if (card is DataStoreHelper.ResumeWatchingResult) {
                                DataStoreHelper.removeLastWatched(card.parentId)
                                viewModel.reloadStored()
                            }
                        }
                    }
                }
            })
        private val bookmarkAdapter = HomeChildItemAdapter(
            id = "bookmarkAdapter".hashCode(),
            nextFocusUp = itemView.nextFocusUpId,
            nextFocusDown = itemView.nextFocusDownId
        ) { callback ->
            if (callback.action != SEARCH_ACTION_SHOW_METADATA) {
                viewModel.click(callback)
                return@HomeChildItemAdapter
            }

            (callback.view.context?.getActivity() as? MainActivity)?.loadPopup(
                callback.card,
                load = false
            )
            /*
            callback.view.context?.getActivity()?.showOptionSelectStringRes(
                callback.view,
                callback.card.posterUrl,
                listOf(
                    R.string.action_open_watching,
                    R.string.action_remove_from_bookmarks,
                ),
                listOf(
                    R.string.action_open_play,
                    R.string.action_open_watching,
                    R.string.action_remove_from_bookmarks
                )
            ) { (isTv, actionId) ->
                when (actionId + if (isTv) 0 else 1) { // play
                    0 -> {
                        viewModel.click(
                            SearchClickCallback(
                                START_ACTION_RESUME_LATEST,
                                callback.view,
                                -1,
                                callback.card
                            )
                        )
                    }

                    1 -> { // info
                        viewModel.click(
                            SearchClickCallback(
                                SEARCH_ACTION_LOAD,
                                callback.view,
                                -1,
                                callback.card
                            )
                        )
                    }

                    2 -> { // remove
                        DataStoreHelper.setResultWatchState(
                            callback.card.id,
                            WatchType.NONE.internalId
                        )
                        viewModel.reloadStored()
                    }
                }
            }
            */
        }

        private val previewViewpager: ViewPager2 =
            itemView.findViewById(R.id.home_preview_viewpager)

        private val previewViewpagerText: ViewGroup =
            itemView.findViewById(R.id.home_preview_viewpager_text)

        // private val previewHeader: FrameLayout = itemView.findViewById(R.id.home_preview)
        private val resumeHolder: View = itemView.findViewById(R.id.home_watch_holder)
        private val resumeRecyclerView: RecyclerView =
            itemView.findViewById(R.id.home_watch_child_recyclerview)
        private val bookmarkHolder: View = itemView.findViewById(R.id.home_bookmarked_holder)
        private val bookmarkRecyclerView: RecyclerView =
            itemView.findViewById(R.id.home_bookmarked_child_recyclerview)

        private val headProfilePic: ImageView? = itemView.findViewById(R.id.home_head_profile_pic)
        private val headProfilePicCard: View? =
            itemView.findViewById(R.id.home_head_profile_padding)

        private val alternateHeadProfilePic: ImageView? =
            itemView.findViewById(R.id.alternate_home_head_profile_pic)
        private val alternateHeadProfilePicCard: View? =
            itemView.findViewById(R.id.alternate_home_head_profile_padding)

        private val topPadding: View? = itemView.findViewById(R.id.home_padding)

        private val alternativeAccountPadding: View? =
            itemView.findViewById(R.id.alternative_account_padding)

        private val homeNonePadding: View = itemView.findViewById(R.id.home_none_padding)

        fun onSelect(item: LoadResponse, position: Int) {
            (binding as? FragmentHomeHeadTvBinding)?.apply {
                homePreviewDescription.isGone = item.plot.isNullOrBlank()
                homePreviewDescription.text = item.plot?.html() ?: ""

                val scoreText = item.score?.toStringNull(0.1, 10, 1, false)

                scoreText?.let { score ->
                    homePreviewScore.text =
                        homePreviewScore.context.getString(R.string.extension_rating, score)

                    // while it should never fail, we do this just in case
                    val rating = score.toDoubleOrNull() ?: item.score?.toDouble() ?: 0.0

                    val color = when {
                        rating < 5.0 -> "#eb2f2f".toColorInt() // Red
                        rating < 8.0 -> "#eda009".toColorInt() // Yellow
                        else -> "#3bb33b".toColorInt() // Green
                    }
                    homePreviewScore.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(color)
                }
                homePreviewScore.isGone = scoreText == null

                item.year?.let { year ->
                    homePreviewYear.text = year.toString()
                }
                homePreviewYear.isGone = item.year == null

                val duration = item.duration
                duration?.let { min ->
                    homePreviewDuration.text =
                        homePreviewDuration.context.getString(R.string.duration_format, min)
                }
                homePreviewDuration.isGone = duration == null || duration <= 0

                val castText = item.actors?.take(3)?.joinToString(", ") { it.actor.name }
                if (!castText.isNullOrBlank()) {
                    homePreviewCast.text =
                        homePreviewCast.context.getString(R.string.cast_format, castText)
                    homePreviewCast.isVisible = true
                } else {
                    homePreviewCast.isVisible = false
                }

                homePreviewText.text = item.name.html()
                populateChips(
                    homePreviewTags,
                    item.tags?.take(6) ?: emptyList(),
                    R.style.ChipFilledSemiTransparent,
                    null
                )


                bindLogo(
                    url = item.logoUrl,
                    headers = item.posterHeaders,
                    titleView = homePreviewText,
                    logoView = homeBackgroundPosterWatermarkBadgeHolder
                )

                homePreviewTags.isGone =
                    item.tags.isNullOrEmpty()

                homePreviewInfoBtt.setOnClickListener { view ->
                    viewModel.click(
                        LoadClickCallback(0, view, position, item)
                    )
                }
            }
            (binding as? FragmentHomeHeadBinding)?.apply {
                //homePreviewImage.setImage(item.posterUrl, item.posterHeaders)

                homePreviewPlay.setOnClickListener { view ->
                    viewModel.click(
                        LoadClickCallback(
                            START_ACTION_RESUME_LATEST,
                            view,
                            position,
                            item
                        )
                    )
                }

                homePreviewInfo.setOnClickListener { view ->
                    viewModel.click(
                        LoadClickCallback(0, view, position, item)
                    )
                }

                // very ugly code, but I don't care
                val id = item.getId()
                val watchType =
                    DataStoreHelper.getResultWatchState(id)
                homePreviewBookmark.setText(watchType.stringRes)
                homePreviewBookmark.setCompoundDrawablesWithIntrinsicBounds(
                    null,
                    ContextCompat.getDrawable(
                        homePreviewBookmark.context,
                        watchType.iconRes
                    ),
                    null,
                    null
                )

                homePreviewBookmark.setOnClickListener { fab ->
                    fab.context.getActivity()?.showBottomDialog(
                        WatchType.entries
                            .map { fab.context.getString(it.stringRes) }
                            .toList(),
                        DataStoreHelper.getResultWatchState(id).ordinal,
                        fab.context.getString(R.string.action_add_to_bookmarks),
                        showApply = false,
                        {}) {
                        val newValue = WatchType.entries[it]

                        ResultViewModel2().updateWatchStatus(
                            newValue,
                            fab.context,
                            item
                        ) { statusChanged: Boolean ->
                            if (!statusChanged) return@updateWatchStatus

                            homePreviewBookmark.setCompoundDrawablesWithIntrinsicBounds(
                                null,
                                ContextCompat.getDrawable(
                                    homePreviewBookmark.context,
                                    newValue.iconRes
                                ),
                                null,
                                null
                            )
                            homePreviewBookmark.setText(newValue.stringRes)
                        }
                    }
                }
            }
        }

        private val previewCallback: ViewPager2.OnPageChangeCallback =
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    previewAdapter.apply {
                        if (position >= itemCount - 1 && hasMoreItems) {
                            hasMoreItems = false // don't make two requests
                            viewModel.loadMoreHomeScrollResponses()
                        }
                    }
                    val item = previewAdapter.getItemOrNull(position) ?: return
                    onSelect(item, position)
                }
            }

        fun onViewDetachedFromWindow() {
            previewViewpager.unregisterOnPageChangeCallback(previewCallback)
        }

        private val toggleList = listOf<Pair<Chip, WatchType>>(
            Pair(itemView.findViewById(R.id.home_type_watching_btt), WatchType.WATCHING),
            Pair(itemView.findViewById(R.id.home_type_completed_btt), WatchType.COMPLETED),
            Pair(itemView.findViewById(R.id.home_type_dropped_btt), WatchType.DROPPED),
            Pair(itemView.findViewById(R.id.home_type_on_hold_btt), WatchType.ONHOLD),
            Pair(itemView.findViewById(R.id.home_plan_to_watch_btt), WatchType.PLANTOWATCH),
        )

        private val toggleListHolder: ChipGroup? = itemView.findViewById(R.id.home_type_holder)

        fun bind() = Unit

        init {
            previewViewpager.setPageTransformer(HomeScrollTransformer())

            previewViewpager.adapter = previewAdapter
            resumeRecyclerView.adapter = resumeAdapter
            bookmarkRecyclerView.setRecycledViewPool(HomeChildItemAdapter.sharedPool)
            bookmarkRecyclerView.adapter = bookmarkAdapter

            resumeRecyclerView.setLinearListLayout(
                nextLeft = R.id.nav_rail_view,
                nextRight = FOCUS_SELF
            )

            bookmarkRecyclerView.setLinearListLayout(
                nextLeft = R.id.nav_rail_view,
                nextRight = FOCUS_SELF
            )

            fixPaddingStatusbarMargin(topPadding)

            for ((chip, watch) in toggleList) {
                chip.isChecked = false
                chip.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        viewModel.loadStoredData(setOf(watch))
                    }
                    // Else if all are unchecked -> Do not load data
                    else if (toggleList.all { !it.first.isChecked }) {
                        viewModel.loadStoredData(emptySet())
                    }
                }
            }

            headProfilePicCard?.isGone = isLayout(TV or EMULATOR)
            alternateHeadProfilePicCard?.isGone = isLayout(TV or EMULATOR)

            fragment.observe(viewModel.currentAccount) { currentAccount ->
                headProfilePic?.loadImage(currentAccount?.image)
                alternateHeadProfilePic?.loadImage(currentAccount?.image)
            }

            headProfilePicCard?.setOnClickListener {
                activity?.showAccountSelectLinear()
            }

            fun showAccountEditBox(context: Context): Boolean {
                val currentAccount = DataStoreHelper.getCurrentAccount()
                return if (currentAccount != null) {
                    showAccountEditDialog(
                        context = context,
                        account = currentAccount,
                        isNewAccount = false,
                        accountEditCallback = { accountViewModel.handleAccountUpdate(it, context) },
                        accountDeleteCallback = {
                            accountViewModel.handleAccountDelete(
                                it,
                                context
                            )
                        }
                    )
                    true
                } else false
            }

            alternateHeadProfilePicCard?.setOnLongClickListener {
                showAccountEditBox(it.context)
            }
            headProfilePicCard?.setOnLongClickListener {
                showAccountEditBox(it.context)
            }

            alternateHeadProfilePicCard?.setOnClickListener {
                activity?.showAccountSelectLinear()
            }

            (binding as? FragmentHomeHeadTvBinding)?.apply {
                /*homePreviewChangeApi.setOnClickListener { view ->
                    view.context.selectHomepage(viewModel.repo?.name) { api ->
                        viewModel.loadAndCancel(api, forceReload = true, fromUI = true)
                    }
                }
                homePreviewReloadProvider.setOnClickListener {
                    viewModel.loadAndCancel(
                        viewModel.apiName.value ?: noneApi.name,
                        forceReload = true,
                        fromUI = true
                    )
                    showToast(R.string.action_reload, Toast.LENGTH_SHORT)
                    true
                }
                homePreviewSearchButton.setOnClickListener { _ ->
                    // Open blank screen.
                    viewModel.queryTextSubmit("")
                }*/

                // A workaround to the focus problem of always centering the view on focus
                // as that causes higher android versions to stretch the ui when switching between shows
                var lastFocusTimeoutMs = 0L
                homePreviewInfoBtt.setOnFocusChangeListener { view, hasFocus ->
                    val lastFocusMs = lastFocusTimeoutMs
                    // Always reset timer, as we only want to update
                    // it if we have not interacted in half a second
                    lastFocusTimeoutMs = System.currentTimeMillis()
                    if (!hasFocus) return@setOnFocusChangeListener
                    if (lastFocusMs + 500L < System.currentTimeMillis()) {
                        MainActivity.centerView(view)
                    }
                }

                homePreviewHiddenNextFocus.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) return@setOnFocusChangeListener
                    previewViewpager.setCurrentItem(previewViewpager.currentItem + 1, true)
                    homePreviewInfoBtt.requestFocus()
                }

                homePreviewHiddenPrevFocus.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) return@setOnFocusChangeListener
                    if (previewViewpager.currentItem <= 0) {
                        //Focus the Home item as the default focus will be the header item
                        (activity as? MainActivity)?.binding?.navRailView?.findViewById<NavigationBarItemView>(
                            R.id.navigation_home
                        )?.requestFocus()
                    } else {
                        previewViewpager.setCurrentItem(previewViewpager.currentItem - 1, true)
                        binding.homePreviewInfoBtt.requestFocus()
                        //binding.homePreviewPlayBtt.requestFocus()
                    }
                }
            }

            (binding as? FragmentHomeHeadBinding)?.apply {
                homeSearch.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                    override fun onQueryTextSubmit(query: String): Boolean {
                        viewModel.queryTextSubmit(query)
                        return true
                    }

                    override fun onQueryTextChange(newText: String): Boolean {
                        viewModel.queryTextChange(newText)
                        return true
                    }
                })

                homeFilterButton.setOnClickListener {
                    showAniListGenreFilter(binding.root.context)
                }
            }
        }

        private fun updatePreview(preview: Resource<Pair<Boolean, List<LoadResponse>>>) {
            if (preview is Resource.Success) {
                homeNonePadding.apply {
                    val params = layoutParams
                    params.height = 0
                    layoutParams = params
                }
            } else fixPaddingStatusbarView(homeNonePadding)

            when (preview) {
                is Resource.Success -> {
                    previewAdapter.submitList(preview.value.second)
                    previewAdapter.hasMoreItems = preview.value.first
                    /*if (!.setItems(
                            preview.value.second,
                            preview.value.first
                        )
                    ) {
                        // this might seam weird and useless, however this prevents a very weird andrid bug were the viewpager is not rendered properly
                        // I have no idea why that happens, but this is my ducktape solution
                        previewViewpager.setCurrentItem(0, false)
                        previewViewpager.beginFakeDrag()
                        previewViewpager.fakeDragBy(1f)
                        previewViewpager.endFakeDrag()
                        previewCallback.onPageSelected(0)
                        //previewHeader.isVisible = true
                    }*/

                    previewViewpager.isVisible = true
                    previewViewpagerText.isVisible = true
                    alternativeAccountPadding?.isVisible = false
                    (binding as? FragmentHomeHeadTvBinding)?.apply {
                        homePreviewInfoBtt.isVisible = true
                    }
                    // Explicitly bind the current item to ensure instant loading
                    val currentPos = previewViewpager.currentItem
                    val item = preview.value.second.getOrNull(currentPos)
                    if (item != null) {
                        onSelect(item, currentPos)
                    }
                }

                else -> {
                    previewAdapter.submitList(listOf())
                    previewViewpager.setCurrentItem(0, false)
                    previewViewpager.isVisible = false
                    previewViewpagerText.isVisible = false
                    alternativeAccountPadding?.isVisible = true
                    (binding as? FragmentHomeHeadTvBinding)?.apply {
                        homePreviewInfoBtt.isVisible = false
                    }
                    //previewHeader.isVisible = false
                }
            }
        }

        private fun updateResume(resumeWatching: List<SearchResponse>) {
            android.util.Log.d("HomeParentItemAdapterPreview", "updateResume() called with ${resumeWatching.size} items, setting visibility to ${resumeWatching.isNotEmpty()}")
            resumeHolder.isVisible = resumeWatching.isNotEmpty()
            resumeAdapter.submitList(resumeWatching)

            if (
                binding is FragmentHomeHeadBinding ||
                binding is FragmentHomeHeadTvBinding &&
                isLayout(EMULATOR)
            ) {
                val title = (binding as? FragmentHomeHeadBinding)?.homeWatchParentItemTitle
                    ?: (binding as? FragmentHomeHeadTvBinding)?.homeWatchParentItemTitle

                title?.setOnClickListener {
                    viewModel.popup(
                        HomeViewModel.ExpandableHomepageList(
                            HomePageList(
                                title.text.toString(),
                                resumeWatching,
                                false
                            ), 1, false
                        ),
                        deleteCallback = {
                            viewModel.deleteResumeWatching()
                        }
                    )
                }
            }
        }

        private fun showAniListGenreFilter(context: Context) {
            // Hardcoded AniList genres
            val genres = listOf(
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

            val selectedGenres = mutableSetOf<String>()
            var selectedYear: String? = "All" // Default to All
            var selectedSeason: String? = "All" // Default to All
            var selectedFormat: String? = "All" // Default to All
            var selectedSort: String? = "All" // Default to All

            val dialog = AlertDialog.Builder(context, R.style.AlertDialogCustom).create()
            val dialogBinding = BottomAnilistGenreTagSelectorBinding.inflate(
                dialog.layoutInflater,
                null,
                false
            )
            dialog.setView(dialogBinding.root)
            dialog.setTitle("Filter")

            // Setup genres adapter
            val genresAdapter = AniListCheckboxAdapter(genres, selectedGenres, { item, isChecked ->
                if (isChecked) {
                    selectedGenres.add(item)
                } else {
                    selectedGenres.remove(item)
                }
                dialogBinding.genresCount.text = "${selectedGenres.size} selected"
            })
            dialogBinding.genresRecycler.adapter = genresAdapter
            dialogBinding.genresRecycler.layoutManager = LinearLayoutManager(context)

            // Setup years adapter (radio mode)
            val selectedYearsSet = if (selectedYear != null) setOf(selectedYear) else emptySet()
            var yearsAdapter: AniListCheckboxAdapter? = null
            yearsAdapter = AniListCheckboxAdapter(years, selectedYearsSet, { item, isChecked ->
                if (isChecked) {
                    selectedYear = item
                } else {
                    selectedYear = "All"
                }
                // Removed '1 selected' text - single-select doesn't need count
                val newSelectedSet = setOf(selectedYear).filterNotNull().toSet()
                dialogBinding.yearRecycler.post {
                    yearsAdapter?.updateSelectedSet(newSelectedSet)
                }
            }, radioMode = true)
            dialogBinding.yearRecycler.adapter = yearsAdapter
            dialogBinding.yearRecycler.layoutManager = LinearLayoutManager(context)

            // Setup seasons adapter (radio mode)
            val selectedSeasonsSet = if (selectedSeason != null) setOf(selectedSeason) else emptySet()
            var seasonsAdapter: AniListCheckboxAdapter? = null
            seasonsAdapter = AniListCheckboxAdapter(seasons, selectedSeasonsSet, { item, isChecked ->
                if (isChecked) {
                    selectedSeason = item
                } else {
                    selectedSeason = "All"
                }
                // Removed '1 selected' text - single-select doesn't need count
                val newSelectedSet = setOf(selectedSeason).filterNotNull().toSet()
                dialogBinding.seasonRecycler.post {
                    seasonsAdapter?.updateSelectedSet(newSelectedSet)
                }
            }, radioMode = true)
            dialogBinding.seasonRecycler.adapter = seasonsAdapter
            dialogBinding.seasonRecycler.layoutManager = LinearLayoutManager(context)

            // Setup formats adapter (radio mode)
            val selectedFormatsSet = if (selectedFormat != null) setOf(selectedFormat) else emptySet()
            var formatsAdapter: AniListCheckboxAdapter? = null
            formatsAdapter = AniListCheckboxAdapter(formats, selectedFormatsSet, { item, isChecked ->
                if (isChecked) {
                    selectedFormat = item
                } else {
                    selectedFormat = "All"
                }
                // Removed '1 selected' text - single-select doesn't need count
                val newSelectedSet = setOf(selectedFormat).filterNotNull().toSet()
                dialogBinding.formatRecycler.post {
                    formatsAdapter?.updateSelectedSet(newSelectedSet)
                }
            }, radioMode = true)
            dialogBinding.formatRecycler.adapter = formatsAdapter
            dialogBinding.formatRecycler.layoutManager = LinearLayoutManager(context)

            // Setup sort adapter (radio mode)
            val selectedSortSet = if (selectedSort != null) setOf(selectedSort) else emptySet()
            var sortAdapter: AniListCheckboxAdapter? = null
            sortAdapter = AniListCheckboxAdapter(sortOptions, selectedSortSet, { item, isChecked ->
                if (isChecked) {
                    selectedSort = item
                } else {
                    selectedSort = "All"
                }
                // Removed '1 selected' text - single-select doesn't need count
                val newSelectedSet = setOf(selectedSort).filterNotNull().toSet()
                dialogBinding.sortRecycler.post {
                    sortAdapter?.updateSelectedSet(newSelectedSet)
                }
            }, radioMode = true)
            dialogBinding.sortRecycler.adapter = sortAdapter
            dialogBinding.sortRecycler.layoutManager = LinearLayoutManager(context)

            // Update initial counts
            dialogBinding.genresCount.text = "0 selected"
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
                selectedGenres.clear()
                selectedYear = "All" // Reset to All
                selectedSeason = "All" // Reset to All
                selectedFormat = "All" // Reset to All
                selectedSort = "All" // Reset to All
                genresAdapter.notifyDataSetChanged()
                yearsAdapter.updateSelectedSet(emptySet())
                seasonsAdapter.updateSelectedSet(emptySet())
                formatsAdapter.updateSelectedSet(emptySet())
                sortAdapter?.updateSelectedSet(emptySet())
                dialogBinding.genresCount.text = "0 selected"
                // Removed '1 selected' text for single-select fields
            }

            // Apply button - fetch AniList results with selected filters
            dialogBinding.applyButton.setOnClickListener {
                android.util.Log.d("GenreFilter", "HomeParentItemAdapterPreview: Apply button clicked")
                android.util.Log.d("GenreFilter", "HomeParentItemAdapterPreview: selectedGenres=$selectedGenres")
                android.util.Log.d("GenreFilter", "HomeParentItemAdapterPreview: selectedYear=$selectedYear")
                android.util.Log.d("GenreFilter", "HomeParentItemAdapterPreview: selectedSeason=$selectedSeason")
                android.util.Log.d("GenreFilter", "HomeParentItemAdapterPreview: selectedFormat=$selectedFormat")
                android.util.Log.d("GenreFilter", "HomeParentItemAdapterPreview: selectedSort=$selectedSort")

                // Validation: year requires season
                if (selectedYear != null && selectedSeason == null) {
                    Toast.makeText(context, "Please select a season when filtering by year", Toast.LENGTH_SHORT).show()
                    android.util.Log.d("GenreFilter", "HomeParentItemAdapterPreview: Validation failed - year without season")
                    return@setOnClickListener
                }

                if (selectedGenres.isNotEmpty() || selectedYear != null || selectedSeason != null || selectedFormat != null || selectedSort != null) {
                    // Convert UI values to API types
                    // "All" converts to null for API (broad search)
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
                    val format = if (selectedFormat == "All") null else com.lagradost.cloudstream3.syncproviders.providers.AniListApi.mapFormatToAniListEnum(selectedFormat)
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

                    android.util.Log.d("GenreFilter", "HomeParentItemAdapterPreview: Converted values - seasonYear=$seasonYear, season=$season, format=$format, sort=$sort")
                    android.util.Log.d("GenreFilter", "HomeParentItemAdapterPreview: Calling pushSearchWithAniListFilters")
                    android.util.Log.d("GenreFilter", "HomeParentItemAdapterPreview: context=$context, isActivity=${context is android.app.Activity}")

                    // Navigate to QuickSearchFragment with AniList filters
                    com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment.pushSearchWithAniListFilters(
                        activity = context as? android.app.Activity,
                        genres = selectedGenres.toList().toTypedArray(),
                        seasonYear = seasonYear,
                        season = season,
                        format = format,
                        sort = sort
                    )
                    android.util.Log.d("GenreFilter", "HomeParentItemAdapterPreview: pushSearchWithAniListFilters called successfully")
                } else {
                    android.util.Log.d("GenreFilter", "HomeParentItemAdapterPreview: No filters selected, dismissing dialog")
                }
                dialog.dismiss()
            }

            dialog.show()
        }

        private fun updateBookmarks(data: Pair<Boolean, List<SearchResponse>>) {
            val (visible, list) = data
            android.util.Log.d("HomeParentItemAdapterPreview", "updateBookmarks() called with visible=$visible, size=${list.size}, setting visibility to $visible")
            bookmarkHolder.isVisible = visible
            bookmarkAdapter.submitList(list)

            if (
                binding is FragmentHomeHeadBinding ||
                binding is FragmentHomeHeadTvBinding &&
                isLayout(EMULATOR)
            ) {
                val title = (binding as? FragmentHomeHeadBinding)?.homeBookmarkParentItemTitle
                    ?: (binding as? FragmentHomeHeadTvBinding)?.homeBookmarkParentItemTitle

                title?.setOnClickListener {
                    val items = toggleList.map { it.first }.filter { it.isChecked }
                    if (items.isEmpty()) return@setOnClickListener // we don't want to show an empty dialog
                    val textSum = items
                        .mapNotNull { it.text }.joinToString()

                    viewModel.popup(
                        HomeViewModel.ExpandableHomepageList(
                            HomePageList(
                                textSum,
                                list,
                                false
                            ), 1, false
                        ), deleteCallback = {
                            viewModel.deleteBookmarks(list)
                        }
                    )
                }
            }
        }

        fun onViewAttachedToWindow() {
            previewViewpager.registerOnPageChangeCallback(previewCallback)

            binding.root.findViewTreeLifecycleOwner()?.apply {
                observe(viewModel.preview) {
                    updatePreview(it)
                }
                /*if (binding is FragmentHomeHeadTvBinding) {
                    observe(viewModel.apiName) { name ->
                        binding.homePreviewChangeApi.text = name
                        binding.homePreviewReloadProvider.isGone = (name == noneApi.name)
                    }
                }*/
                observe(viewModel.resumeWatching) {
                    android.util.Log.d("HomeParentItemAdapterPreview", "resumeWatching observer received ${it.size} items")
                    updateResume(it)
                }
                observe(viewModel.bookmarks) {
                    android.util.Log.d("HomeParentItemAdapterPreview", "bookmarks observer received visible=${it.first}, size=${it.second.size}")
                    updateBookmarks(it)
                }
                // Load stored data after observers are attached to ensure they receive the data
                viewModel.reloadStored()
                observe(viewModel.availableWatchStatusTypes) { (checked, visible) ->
                    for ((chip, watch) in toggleList) {
                        chip.apply {
                            isVisible = visible.contains(watch)
                            isChecked = checked.contains(watch)
                        }
                    }
                    toggleListHolder?.isGone = visible.isEmpty()
                }
            } ?: debugException { "Expected findViewTreeLifecycleOwner" }
        }
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
                // Radio mode - use highlighted selection layout
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_anilist_radio_selection, parent, false)
                RadioViewHolder(view)
            } else {
                // Checkbox mode - use checkbox layout
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
