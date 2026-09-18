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
     * Uses link name heuristic: "dub"/"dubbed" in name = Dubbed, "sub"/"subbed" in name = Subbed.
     * Returns true if the link matches the preference, or if preference is ANY.
     */
    fun matchesAudioPreference(
        link: com.lagradost.cloudstream3.utils.ExtractorLink,
        preferredAudio: AudioPref,
        episodeDubStatus: DubStatus?
    ): Boolean {
        if (preferredAudio == AudioPref.ANY) return true

        // If the episode has a known dub status, use it directly
        if (episodeDubStatus != null && episodeDubStatus != DubStatus.None) {
            return when (preferredAudio) {
                AudioPref.DUB -> episodeDubStatus == DubStatus.Dubbed
                AudioPref.SUB -> episodeDubStatus == DubStatus.Subbed
                AudioPref.ANY -> true
            }
        }

        // Heuristic: scan link name for dub/sub indicators
        val linkName = link.name.lowercase()
        val hasDubIndicator = linkName.contains("dub") || linkName.contains("dubbed")
        val hasSubIndicator = linkName.contains("sub") || linkName.contains("subbed")

        // If no indicators found, assume it matches (don't filter out unknown links)
        if (!hasDubIndicator && !hasSubIndicator) return true

        return when (preferredAudio) {
            AudioPref.DUB -> hasDubIndicator
            AudioPref.SUB -> hasSubIndicator
            AudioPref.ANY -> true
        }
    }

    /**
     * Filters and sorts links based on user download preferences.
     * Hierarchy: Source → Quality → Audio.
     * If preferred sources are configured, those are tried first.
     * Quality is a cap semantics. Audio is best-effort.
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

        // Step 1: Source preference — narrow to selected sources first
        val sourceFiltered = if (preferredSources.isNotEmpty()) {
            allLinks.filter { link ->
                preferredSources.any { pref ->
                    link.source.contains(pref, ignoreCase = true) ||
                        link.name.contains(pref, ignoreCase = true)
                }
            }
        } else emptyList()

        // Use preferred sources if any matched, otherwise fall through to all links
        val candidates = sourceFiltered.ifEmpty { allLinks }

        // Step 2: Audio filtering — best-effort, never drops everything
        val audioMatched = if (preferredAudio == AudioPref.ANY) {
            candidates
        } else {
            val matched = candidates.filter {
                matchesAudioPreference(it, preferredAudio, episodeDubStatus)
            }
            if (matched.isEmpty()) candidates else matched
        }

        // Step 3: Quality filtering — cap semantics
        if (preferredQuality == null) {
            return audioMatched.sortedByDescending { it.quality }
        }

        val capped = audioMatched.filter { it.quality > 0 && it.quality <= preferredQuality }
        if (capped.isNotEmpty()) {
            return capped.sortedByDescending { it.quality }
        }

        val known = audioMatched.filter { it.quality > 0 }
        if (known.isNotEmpty()) {
            return known.sortedByDescending { it.quality }
        }

        return audioMatched
    }
}
