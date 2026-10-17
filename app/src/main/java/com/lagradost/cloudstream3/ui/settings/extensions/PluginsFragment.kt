package com.lagradost.cloudstream3.ui.settings.extensions

import android.app.Activity
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.appbar.MaterialToolbar
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.databinding.FragmentPluginsBinding
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.bindChips
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setSystemBarsPadding
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setToolBarScrollFlags
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiProviderLangSettings
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showMultiDialog
import com.lagradost.cloudstream3.utils.SubtitleHelper.getNameNextToFlagEmoji
import com.lagradost.cloudstream3.utils.UIHelper.toPx

const val PLUGINS_BUNDLE_NAME = "name"
const val PLUGINS_BUNDLE_URL = "url"
const val PLUGINS_BUNDLE_LOCAL = "isLocal"

/** Menu id of the universal "block extension popups" toolbar toggle. */
const val MENU_BLOCK_PLUGIN_POPUPS = 0x7f0b0001

/** Adds (or refreshes) the top-right toolbar toggle that mutes popups from
 * EVERY extension repository — the icon shows the current state. */
fun MaterialToolbar.addPopupBlockToggle() {
    var item = menu.findItem(MENU_BLOCK_PLUGIN_POPUPS)
    if (item == null) {
        item = menu.add(Menu.NONE, MENU_BLOCK_PLUGIN_POPUPS, Menu.NONE, R.string.block_plugin_popups_menu)
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
    }
    item.setIcon(
        if (PluginManager.isPopupBlockingEnabled())
            R.drawable.baseline_notifications_none_24
        else R.drawable.ic_baseline_notifications_active_24
    )
}

/** Flips the universal popup kill switch and updates the toolbar icon. */
fun MenuItem.togglePopupBlocking(activity: Activity?) {
    val nowEnabled = !PluginManager.isPopupBlockingEnabled()
    PluginManager.setPopupBlockingEnabled(nowEnabled)
    setIcon(
        if (nowEnabled)
            R.drawable.baseline_notifications_none_24
        else R.drawable.ic_baseline_notifications_active_24
    )
    showToast(
        activity,
        if (nowEnabled) R.string.plugin_popups_on else R.string.plugin_popups_off
    )
}

class PluginsFragment : BaseFragment<FragmentPluginsBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentPluginsBinding::inflate)
) {
    private lateinit var pluginViewModel: PluginsViewModel

    override fun onDestroyView() {
        pluginViewModel.clear() // clear for the next observe
        super.onDestroyView()
    }

    override fun fixLayout(view: View) {
        setSystemBarsPadding()
    }

    override fun onBindingCreated(binding: FragmentPluginsBinding) {
        pluginViewModel = ViewModelProvider(this)[PluginsViewModel::class.java]

        // Since the ViewModel is getting reused the tvTypes must be cleared between uses
        pluginViewModel.tvTypes.clear()
        pluginViewModel.selectedLanguages = listOf()
        pluginViewModel.clear()

        // Filter by language set on preferred media
        activity?.let {
            val providerLangs = it.getApiProviderLangSettings().toList()
            if (!providerLangs.contains(AllLanguagesName)) {
                pluginViewModel.selectedLanguages = mutableListOf("none") + providerLangs
            }
        }

        val name = arguments?.getString(PLUGINS_BUNDLE_NAME)
        val url = arguments?.getString(PLUGINS_BUNDLE_URL)
        val isLocal = arguments?.getBoolean(PLUGINS_BUNDLE_LOCAL) == true
        // download all extensions button
        val downloadAllButton = binding.settingsToolbar.menu?.findItem(R.id.download_all)

        if (url == null || name == null) {
            dispatchBackPressed()
            return
        }

        setToolBarScrollFlags()
        setUpToolbar(name)
        binding.settingsToolbar.apply {
            addPopupBlockToggle()
            setOnMenuItemClickListener { menuItem ->
                when (menuItem?.itemId) {
                    R.id.download_all -> {
                        PluginsViewModel.downloadAll(activity, url, pluginViewModel)
                    }

                    R.id.lang_filter -> {
                        val languagesTagName = pluginViewModel.pluginLanguages
                            .map { langTag ->
                                Pair(
                                    langTag,
                                    getNameNextToFlagEmoji(langTag) ?: langTag
                                )
                            }
                            .sortedBy {
                                it.second.substringAfter("\u00a0").lowercase()
                            } // name ignoring flag emoji
                            .toMutableList()

                        // Move "none" to 1st position as it's special code to indicate unknown/missing language
                        if (languagesTagName.remove(Pair("none", "none"))) {
                            languagesTagName.add(0, Pair("none", getString(R.string.no_data)))
                        }

                        val currentIndexList = pluginViewModel.selectedLanguages.map { langTag ->
                            languagesTagName.indexOfFirst { lang -> lang.first == langTag }
                        }

                        activity?.showMultiDialog(
                            languagesTagName.map { it.second },
                            currentIndexList,
                            getString(R.string.provider_lang_settings),
                            {}
                        ) { selectedList ->
                            pluginViewModel.selectedLanguages =
                                selectedList.map { languagesTagName[it].first }
                            pluginViewModel.updateFilteredPlugins()
                        }
                    }

                    MENU_BLOCK_PLUGIN_POPUPS -> {
                        menuItem?.togglePopupBlocking(activity)
                    }

                    else -> {}
                }
                return@setOnMenuItemClickListener true
            }

            val searchView =
                menu?.findItem(R.id.search_button)?.actionView as? SearchView

            // Don't go back if active query
            setNavigationOnClickListener {
                if (searchView?.isIconified == false) {
                    searchView.isIconified = true
                } else {
                    dispatchBackPressed()
                }
            }

            searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    pluginViewModel.search(query)
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    pluginViewModel.search(newText)
                    return true
                }
            })
        }
//        searchView?.onActionViewCollapsed = {
//            pluginViewModel.search(null)
//        }

        // Because onActionViewCollapsed doesn't wanna work we need this workaround :(

        binding.pluginRecyclerView.apply {
            setLinearListLayout(
                isHorizontal = false,
                nextDown = FOCUS_SELF,
                nextRight = FOCUS_SELF,
            )
            setRecycledViewPool(PluginAdapter.sharedPool)
            adapter =
                PluginAdapter {
                    pluginViewModel.handlePluginAction(activity, url, it, isLocal)
                }
        }

        if (isLayout(TV or EMULATOR)) {
            // Scrolling down does not reveal the whole RecyclerView on TV, add to bypass that.
            binding.pluginRecyclerView.setPadding(0, 0, 0, 200.toPx)
        }

        observe(pluginViewModel.filteredPlugins) { (scrollToTop, list) ->
            (binding.pluginRecyclerView.adapter as? PluginAdapter)?.submitList(list)
            if (scrollToTop) {
                binding.pluginRecyclerView.scrollToPosition(0)
            }
        }

        if (isLocal) {
            // No download button and no categories on local
            downloadAllButton?.isVisible = false
            binding.settingsToolbar.menu?.findItem(R.id.lang_filter)?.isVisible = false
            pluginViewModel.updatePluginListLocal()

            binding.tvtypesChipsScroll.root.isVisible = false
        } else {
            pluginViewModel.updatePluginList(context, url)
            binding.tvtypesChipsScroll.root.isVisible = true
            // not needed for users but may be useful for devs
            downloadAllButton?.isVisible = BuildConfig.DEBUG

            bindChips(
                binding.tvtypesChipsScroll.tvtypesChips,
                emptyList(),
                TvType.entries.toList(),
                callback = { list ->
                    pluginViewModel.tvTypes.clear()
                    pluginViewModel.tvTypes.addAll(list.map { it.name })
                    pluginViewModel.updateFilteredPlugins()
                },
                nextFocusDown = R.id.plugin_recycler_view,
                nextFocusUp = null,
            )
        }
    }

    companion object {
        fun newInstance(name: String, url: String, isLocal: Boolean): Bundle {
            return Bundle().apply {
                putString(PLUGINS_BUNDLE_NAME, name)
                putString(PLUGINS_BUNDLE_URL, url)
                putBoolean(PLUGINS_BUNDLE_LOCAL, isLocal)
            }
        }

//        class RepoSearchView(context: Context) : android.widget.SearchView(context) {
//            var onActionViewCollapsed = {}
//
//            override fun onActionViewCollapsed() {
//                onActionViewCollapsed()
//            }
//        }

    }
}