package com.lagradost.cloudstream3.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.edit
import androidx.navigation.fragment.findNavController
import androidx.navigation.NavOptions
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.BasePreferenceFragmentCompat
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.getPref
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setPaddingBottom
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setToolBarScrollFlags
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiDubstatusSettings
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiProviderLangSettings
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showMultiDialog
import com.lagradost.cloudstream3.utils.SubtitleHelper.getNameNextToFlagEmoji
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard

class SettingsProviders : BasePreferenceFragmentCompat() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(R.string.category_providers)
        setPaddingBottom()
        setToolBarScrollFlags()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        hideKeyboard()
        setPreferencesFromResource(R.xml.settings_providers, rootKey)
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(requireContext())

        getPref(R.string.display_sub_key)?.setOnPreferenceClickListener {
            activity?.getApiDubstatusSettings()?.let { current ->
                val dublist = DubStatus.entries
                val names = dublist.map { it.name }

                val currentList = ArrayList<Int>()
                for (i in current) {
                    currentList.add(dublist.indexOf(i))
                }

                activity?.showMultiDialog(
                    names,
                    currentList,
                    getString(R.string.display_subbed_dubbed_settings),
                    {}
                ) { selectedList ->
                    APIRepository.dubStatusActive = selectedList.map { dublist[it] }.toHashSet()
                    settingsManager.edit {
                        putStringSet(
                            getString(R.string.display_sub_key),
                            selectedList.map { names[it] }.toMutableSet()
                        )
                    }
                }
            }

            return@setOnPreferenceClickListener true
        }

        getPref(R.string.tmdb_display_language_key)?.setOnPreferenceClickListener {
            // TMDB display languages - common ones with proper codes
            val languages = listOf(
                "English" to "en-US",
                "Español" to "es-ES", 
                "Français" to "fr-FR",
                "Deutsch" to "de-DE",
                "Italiano" to "it-IT",
                "Português" to "pt-BR",
                "Русский" to "ru-RU",
                "日本語" to "ja-JP",
                "한국어" to "ko-KR",
                "中文" to "zh-CN",
                "العربية" to "ar-SA",
                "Türkçe" to "tr-TR",
                "हिन्दी" to "hi-IN",
                "Nederlands" to "nl-NL",
                "Svenska" to "sv-SE",
                "Norsk" to "no-NO",
                "Dansk" to "da-DK",
                "Suomi" to "fi-FI",
                "Ελληνικά" to "el-GR",
                "Polski" to "pl-PL",
                "Čeština" to "cs-CZ",
                "Magyar" to "hu-HU",
                "Română" to "ro-RO",
                "עברית" to "he-IL",
                "Українська" to "uk-UA"
            )
            
            val currentLanguage = settingsManager.getString(getString(R.string.tmdb_display_language_key), "en-US") ?: "en-US"
            val currentIndex = languages.indexOfFirst { it.second == currentLanguage }.coerceAtLeast(0)
            
            activity?.showMultiDialog(
                languages.map { it.first },
                listOf(currentIndex),
                getString(R.string.tmdb_display_language_settings),
                {}
            ) { selectedList ->
                val selectedCode = if (selectedList.isNotEmpty()) {
                    languages[selectedList.first()].second
                } else {
                    "en-US" // fallback
                }
                
                settingsManager.edit {
                    putString(getString(R.string.tmdb_display_language_key), selectedCode)
                }
                
                // Clear genre cache when language changes
                com.lagradost.cloudstream3.ui.browse.TmdbFilterUtils.clearGenreCache()
                
                Toast.makeText(requireContext(), "TMDB language changed to ${selectedCode}", Toast.LENGTH_SHORT).show()
            }

            return@setOnPreferenceClickListener true
        }

        getPref(R.string.test_providers_key)?.setOnPreferenceClickListener {
            // Somehow animations do not work without this.
            val options = NavOptions.Builder()
                .setEnterAnim(R.anim.enter_anim)
                .setExitAnim(R.anim.exit_anim)
                .setPopEnterAnim(R.anim.pop_enter)
                .setPopExitAnim(R.anim.pop_exit)
                .build()

            this@SettingsProviders.findNavController()
                .navigate(R.id.navigation_test_providers, null, options)
            true
        }

        getPref(R.string.prefer_media_type_key)?.setOnPreferenceClickListener {
            val names = enumValues<TvType>().sorted().map { it.name }
            val default =
                enumValues<TvType>().sorted().filter { it != TvType.NSFW }.map { it.ordinal }
            val defaultSet = default.map { it.toString() }.toSet()
            val currentList = try {
                settingsManager.getStringSet(getString(R.string.prefer_media_type_key), defaultSet)
                    ?.map {
                        it.toInt()
                    }
            } catch (e: Throwable) {
                null
            } ?: default

            activity?.showMultiDialog(
                names,
                currentList,
                getString(R.string.preferred_media_settings),
                {}
            ) { selectedList ->
                settingsManager.edit {
                    putStringSet(
                        getString(R.string.prefer_media_type_key),
                        selectedList.map { it.toString() }.toMutableSet()
                    )
                }
                DataStoreHelper.currentHomePage = null
                //(context ?: CloudStreamApp.context)?.let { ctx -> app.initClient(ctx) }
            }

            return@setOnPreferenceClickListener true
        }

        getPref(R.string.provider_lang_key)?.setOnPreferenceClickListener {
            activity?.getApiProviderLangSettings()?.let { currentLangTags ->
                val languagesTagName = synchronized(APIHolder.apis) {
                    listOf( Pair(AllLanguagesName, getString(R.string.all_languages_preference)) ) +
                    APIHolder.apis.map { Pair(it.lang, getNameNextToFlagEmoji(it.lang) ?: it.lang) }
                        .toSet().sortedBy { it.second.substringAfter("\u00a0").lowercase() } // name ignoring flag emoji
                }

                val currentIndexList = currentLangTags.map { langTag ->
                    languagesTagName.indexOfFirst { lang -> lang.first == langTag }
                }

                activity?.showMultiDialog(
                    languagesTagName.map { it.second },
                    currentIndexList,
                    getString(R.string.provider_lang_settings),
                    {}
                ) { selectedList ->
                    settingsManager.edit {
                        putStringSet(
                            getString(R.string.provider_lang_key),
                            selectedList.map { languagesTagName[it].first }.toSet()
                        )
                    }
                    // APIRepository.providersActive = it.context.getApiSettings()
                }
            }

            return@setOnPreferenceClickListener true
        }
    }
}
