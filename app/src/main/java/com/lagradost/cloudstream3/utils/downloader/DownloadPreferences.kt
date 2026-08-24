package com.lagradost.cloudstream3.utils.downloader

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.R

object DownloadPreferences {
    private const val TAG = "DownloadPreferences"

    data class DownloadPrefs(
        val preferredQuality: Int?,
        val preferredAudio: AudioPref,
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

        return DownloadPrefs(preferredQuality, preferredAudio)
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
     * Quality preference is treated as a CAP: the highest quality at or below
     * the preferred value is chosen. If nothing is at or below the cap,
     * the best available link is used instead (rather than downloading
     * something far worse than requested).
     */
    fun selectBestLinks(
        context: Context,
        allLinks: List<com.lagradost.cloudstream3.utils.ExtractorLink>,
        episodeDubStatus: DubStatus?
    ): List<com.lagradost.cloudstream3.utils.ExtractorLink> {
        if (allLinks.isEmpty()) return emptyList()

        val prefs = getPreferences(context)
        val preferredQuality = prefs.preferredQuality
        val preferredAudio = prefs.preferredAudio

        // Best-effort audio filtering: prefer links matching the audio pref,
        // but never drop everything if the heuristic misses
        val audioMatched = if (preferredAudio == AudioPref.ANY) {
            allLinks
        } else {
            val matched = allLinks.filter {
                matchesAudioPreference(it, preferredAudio, episodeDubStatus)
            }
            if (matched.isEmpty()) allLinks else matched
        }

        // No quality preference: best available wins
        if (preferredQuality == null) {
            return audioMatched.sortedByDescending { it.quality }
        }

        // Cap semantics: highest quality at or below the preferred value
        val capped = audioMatched.filter { it.quality > 0 && it.quality <= preferredQuality }
        if (capped.isNotEmpty()) {
            return capped.sortedByDescending { it.quality }
        }

        // Nothing at or below the cap: use the closest available (best), so the
        // user still gets a working download instead of a silent mismatch
        val known = audioMatched.filter { it.quality > 0 }
        if (known.isNotEmpty()) {
            return known.sortedByDescending { it.quality }
        }

        return audioMatched
    }
}
