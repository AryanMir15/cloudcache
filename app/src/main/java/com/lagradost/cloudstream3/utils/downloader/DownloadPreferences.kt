package com.lagradost.cloudstream3.utils.downloader

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper

object DownloadPreferences {
    private const val TAG = "DownloadPreferences"

    data class DownloadPrefs(
        val preferredQuality: Int?,
        val preferredAudio: AudioPref,
        val preferredSources: List<String>,
    )

    enum class AudioPref {
        SUB, DUB, ANY
    }

    fun getPreferences(context: Context): DownloadPrefs {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
        val qualityKey = settingsManager.getString(
            context.getString(R.string.download_quality_pref_key),
            "best"
        )
        val audioKey = settingsManager.getString(
            context.getString(R.string.download_audio_pref_key),
            "any"
        )

        val preferredQuality = when (qualityKey) {
            "best" -> null
            else -> qualityKey?.toIntOrNull()
        }

        val preferredAudio = when (audioKey) {
            "sub" -> AudioPref.SUB
            "dub" -> AudioPref.DUB
            else -> AudioPref.ANY
        }

        val preferredSources = QualityDataHelper.getSelectedSources()

        return DownloadPrefs(preferredQuality, preferredAudio, preferredSources)
    }

    /**
     * Returns the target quality height to prefer for downloads.
     * null means "best available" (no preference filtering).
     */
    fun getPreferredQualityHeight(context: Context): Int? {
        return getPreferences(context).preferredQuality
    }

    /**
     * Returns the preferred audio type for downloads.
     */
    fun getPreferredAudio(context: Context): AudioPref {
        return getPreferences(context).preferredAudio
    }

    /**
     * Determines if an ExtractorLink matches the preferred DubStatus.
     * A link that names its audio ("dub"/"dubbed"/"sub"/"subbed") is
     * authoritative for itself — the episode's dubStatus is the primary
     * provider's view and can conflict with merged secondary links (e.g. the
     * primary lists Dubbed while the user wants SUB, or a Sub+Dub pool where
     * the episode status is None). Only links with no name signal fall back to
     * the episode's dubStatus. Returns true if the preference is ANY or the
     * link is genuinely unidentifiable.
     */
    fun matchesAudioPreference(
        link: com.lagradost.cloudstream3.utils.ExtractorLink,
        preferredAudio: AudioPref,
        episodeDubStatus: DubStatus?
    ): Boolean {
        if (preferredAudio == AudioPref.ANY) return true

        // Link-name signal first — per-link truth, especially for merged
        // secondary sources that tag variants "(Sub)"/"(Dub)".
        val linkName = link.name.lowercase()
        val hasDubIndicator = linkName.contains("dub") || linkName.contains("dubbed")
        val hasSubIndicator = linkName.contains("sub") || linkName.contains("subbed")
        if (hasDubIndicator || hasSubIndicator) {
            return when (preferredAudio) {
                AudioPref.DUB -> hasDubIndicator
                AudioPref.SUB -> hasSubIndicator
                AudioPref.ANY -> true
            }
        }

        // No name signal: fall back to the episode's known dub status
        if (episodeDubStatus != null && episodeDubStatus != DubStatus.None) {
            return when (preferredAudio) {
                AudioPref.DUB -> episodeDubStatus == DubStatus.Dubbed
                AudioPref.SUB -> episodeDubStatus == DubStatus.Subbed
                AudioPref.ANY -> true
            }
        }

        // Unknown — assume it matches (don't filter out unknown links)
        return true
    }

    /**
     * Filters and sorts links based on user download preferences.
     * Preferred sources are tried first (in the user's source-priority order),
     * followed by every other link as fallback. Quality is a cap semantics.
     * Audio is best-effort.
     */
    fun selectBestLinks(
        context: Context,
        allLinks: List<com.lagradost.cloudstream3.utils.ExtractorLink>,
        episodeDubStatus: DubStatus?
    ): List<com.lagradost.cloudstream3.utils.ExtractorLink> {
        if (allLinks.isEmpty()) return emptyList()

        // Wrap preference reading in try-catch — a crash here should never prevent download
        val prefs = try {
            getPreferences(context)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to read download preferences, using all links", e)
            return allLinks.sortedByDescending { it.quality }
        }

        val preferredQuality = prefs.preferredQuality
        val preferredAudio = prefs.preferredAudio
        val preferredSources = prefs.preferredSources

        // Step 1: Audio filtering — best-effort, never drops everything
        val audioMatched = if (preferredAudio == AudioPref.ANY) {
            allLinks
        } else {
            val matched = allLinks.filter {
                matchesAudioPreference(it, preferredAudio, episodeDubStatus)
            }
            if (matched.isEmpty()) allLinks else matched
        }

        // Step 2: Quality filtering — cap semantics
        val ordered = if (preferredQuality == null) {
            audioMatched.sortedByDescending { it.quality }
        } else {
            val capped = audioMatched.filter { it.quality > 0 && it.quality <= preferredQuality }
            if (capped.isNotEmpty()) {
                capped.sortedByDescending { it.quality }
            } else {
                val known = audioMatched.filter { it.quality > 0 }
                if (known.isNotEmpty()) {
                    known.sortedByDescending { it.quality }
                } else {
                    audioMatched
                }
            }
        }

        // Step 3: Preferred sources first in the user's priority order; all
        // remaining links stay queued as fallback so a failed preferred link
        // does not abort the download.
        if (preferredSources.isEmpty()) return ordered

        fun sourcePriority(link: com.lagradost.cloudstream3.utils.ExtractorLink): Int {
            val index = preferredSources.indexOfFirst { pref ->
                link.source.contains(pref, ignoreCase = true) ||
                    link.name.contains(pref, ignoreCase = true)
            }
            return if (index < 0) Int.MAX_VALUE else index
        }

        // Tie-break within the same source: audio-matching links first, so a
        // DUB link listed above a SUB link in the provider pool can never win
        // over the user's chosen audio when both are otherwise equal.
        fun audioPriority(link: com.lagradost.cloudstream3.utils.ExtractorLink): Int {
            return if (matchesAudioPreference(link, preferredAudio, episodeDubStatus)) 0 else 1
        }

        return ordered.sortedWith(
            compareBy(::sourcePriority).thenBy(::audioPriority)
        )
    }
}
