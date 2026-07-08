package com.lagradost.cloudstream3.ui.schedule

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

enum class ScheduleType(val displayName: String) {
    ANIME("Anime"),
    TV("TV Shows")
}

data class WeeklyScheduleItem(
    val scheduleId: Int,
    val scheduleName: String,
    val schedulePosterUrl: String?,
    val scheduleBannerUrl: String?,
    val scheduleLogoUrl: String?,
    val episodeNumber: Int?,
    val airingAt: Long,
    val scheduleType: ScheduleType,
    val tmdbId: Int? = null
) : SearchResponse {
    override var id: Int? = scheduleId
    override val name: String = scheduleName
    override val url: String = "schedule://$scheduleId"
    override val apiName: String = "Schedule"
    override var type: TvType? = when (scheduleType) {
        ScheduleType.ANIME -> TvType.Anime
        ScheduleType.TV -> TvType.TvSeries
    }
    override var posterUrl: String? = schedulePosterUrl
    override var posterHeaders: Map<String, String>? = null
    override var quality: SearchQuality? = null
    override var score: Score? = null
    override var tags: List<String>? = null

    val airingDay: DayOfWeek
        get() = Instant.ofEpochMilli(airingAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .dayOfWeek

    val airingDate: LocalDate
        get() = Instant.ofEpochMilli(airingAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()

    val relativeDayLabel: String
        get() {
            val today = LocalDate.now()
            return when (airingDate) {
                today -> "Today"
                today.plusDays(1) -> "Tomorrow"
                else -> airingDay.getDisplayName(TextStyle.FULL, Locale.getDefault())
            }
        }
}

data class DaySchedule(
    val dayOfWeek: DayOfWeek,
    val items: List<WeeklyScheduleItem>
) {
    val dayLabel: String
        get() = dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())

    val isToday: Boolean
        get() = dayOfWeek == LocalDate.now().dayOfWeek
}
