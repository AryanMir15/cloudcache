package com.lagradost.cloudstream3.ui.result.swap

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse

/**
 * Type-safe metadata snapshot that normalizes all fields across LoadResponse types
 * with non-null guarantees. This prevents null field propagation during merge operations.
 */
data class MetadataSnapshot(
    val posterUrl: String = "",
    val bannerUrl: String = "",
    val logoUrl: String = "",
    val plot: String = "",
    val actors: List<ActorData> = emptyList(),
    val score: Int = 0,
    val year: Int = 0,
    val showStatus: String = "",
    val tags: List<String> = emptyList()
) {
    companion object {
        /**
         * Create a MetadataSnapshot from any LoadResponse type
         * Extracts fields with defaults for null values
         */
        fun from(response: LoadResponse): MetadataSnapshot = when(response) {
            is AnimeLoadResponse -> fromAnimeLoadResponse(response)
            is TvSeriesLoadResponse -> fromTvSeriesLoadResponse(response)
            else -> fromGenericLoadResponse(response)
        }

        private fun fromAnimeLoadResponse(response: AnimeLoadResponse): MetadataSnapshot {
            return MetadataSnapshot(
                posterUrl = response.posterUrl ?: "",
                bannerUrl = response.backgroundPosterUrl ?: "",
                logoUrl = response.logoUrl ?: "",
                plot = response.plot ?: "",
                actors = response.actors ?: emptyList(),
                score = response.score?.toInt() ?: 0,
                year = response.year ?: 0,
                showStatus = response.showStatus?.name ?: "",
                tags = response.tags ?: emptyList()
            )
        }

        private fun fromTvSeriesLoadResponse(response: TvSeriesLoadResponse): MetadataSnapshot {
            return MetadataSnapshot(
                posterUrl = response.posterUrl ?: "",
                bannerUrl = response.backgroundPosterUrl ?: "",
                logoUrl = response.logoUrl ?: "",
                plot = response.plot ?: "",
                actors = response.actors ?: emptyList(),
                score = response.score?.toInt() ?: 0,
                year = response.year ?: 0,
                showStatus = response.showStatus?.name ?: "",
                tags = response.tags ?: emptyList()
            )
        }

        private fun fromGenericLoadResponse(response: LoadResponse): MetadataSnapshot {
            return MetadataSnapshot(
                posterUrl = response.posterUrl ?: "",
                bannerUrl = response.backgroundPosterUrl ?: "",
                logoUrl = response.logoUrl ?: "",
                plot = response.plot ?: "",
                actors = emptyList(),
                score = response.score?.toInt() ?: 0,
                year = response.year ?: 0,
                showStatus = "",
                tags = response.tags ?: emptyList()
            )
        }
    }

    /**
     * Apply this snapshot to a target LoadResponse
     * Returns a new LoadResponse with the snapshot's values applied
     */
    fun applyTo(target: LoadResponse): LoadResponse {
        return when (target) {
            is AnimeLoadResponse -> applyToAnimeLoadResponse(target)
            is TvSeriesLoadResponse -> applyToTvSeriesLoadResponse(target)
            else -> applyToGenericLoadResponse(target)
        }
    }

    private fun applyToAnimeLoadResponse(target: AnimeLoadResponse): AnimeLoadResponse {
        return target.copy(
            posterUrl = posterUrl.ifBlank { target.posterUrl },
            backgroundPosterUrl = bannerUrl.ifBlank { target.backgroundPosterUrl },
            logoUrl = logoUrl.ifBlank { target.logoUrl },
            plot = plot.ifBlank { target.plot },
            actors = actors.ifEmpty { target.actors },
            score = if (score > 0) score.toScore() else target.score,
            year = if (year > 0) year else target.year,
            showStatus = if (showStatus.isNotBlank()) showStatus.toShowStatus() else target.showStatus,
            tags = tags.ifEmpty { target.tags }
        )
    }

    private fun applyToTvSeriesLoadResponse(target: TvSeriesLoadResponse): TvSeriesLoadResponse {
        return target.copy(
            posterUrl = posterUrl.ifBlank { target.posterUrl },
            backgroundPosterUrl = bannerUrl.ifBlank { target.backgroundPosterUrl },
            logoUrl = logoUrl.ifBlank { target.logoUrl },
            plot = plot.ifBlank { target.plot },
            actors = actors.ifEmpty { target.actors },
            score = if (score > 0) score.toScore() else target.score,
            year = if (year > 0) year else target.year,
            showStatus = if (showStatus.isNotBlank()) showStatus.toShowStatus() else target.showStatus,
            tags = tags.ifEmpty { target.tags }
        )
    }

    private fun applyToGenericLoadResponse(target: LoadResponse): LoadResponse {
        // For generic LoadResponse, we can only modify common fields
        // Since LoadResponse is an interface, we return the target as-is
        return target
    }

    // Helper functions for type conversions
    private fun Int.toScore(): com.lagradost.cloudstream3.Score {
        // Use the from method which is the proper way to create Score
        return com.lagradost.cloudstream3.Score.from(this, this)!!
    }

    private fun String.toShowStatus(): com.lagradost.cloudstream3.ShowStatus? {
        return com.lagradost.cloudstream3.ShowStatus.entries.find { it.name.equals(this, ignoreCase = true) }
    }
}
