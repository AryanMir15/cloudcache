package com.lagradost.cloudstream3.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi

/**
 * Shared utilities for AniList genre/tag filtering
 * Reduces code duplication across SearchFragment, QuickSearchFragment, and HomeParentItemAdapterPreview
 */
object AniListFilterUtils {

    /**
     * Combined list of all AniList genres and tags
     * Hardcoded for performance - these values don't change frequently
     */
    val ALL_GENRES_AND_TAGS = listOf(
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

    /**
     * UI-only separation: genres (first 18 items) vs tags (rest)
     */
    val GENRES = ALL_GENRES_AND_TAGS.take(18)
    val TAGS = ALL_GENRES_AND_TAGS.drop(18)

    /**
     * Hardcoded filter options
     */
    val YEARS = listOf("All") + (1940..2027).reversed().map { it.toString() }
    val SEASONS = listOf("All", "Winter", "Spring", "Summer", "Fall")
    val FORMATS = listOf("All", "TV Show", "Movie", "TV Short", "Special", "OVA", "ONA", "Music")
    val SORT_OPTIONS = listOf("All") + listOf(
        "Popularity" to "POPULARITY_DESC",
        "Average Score" to "SCORE_DESC",
        "Trending" to "TRENDING_DESC",
        "Favorites" to "FAVOURITES_DESC",
        "Title" to "TITLE_ROMAJI",
        "Date Added" to "ID_DESC",
        "Release Date" to "START_DATE_DESC"
    ).map { it.first }

    /**
     * Checks if a provider is anime-focused
     */
    fun isAnimeProvider(providerName: String): Boolean {
        val api = getApiFromNameNull(providerName) ?: return false
        return api.supportedTypes.any { type ->
            type == TvType.Anime || type == TvType.OVA || type == TvType.AnimeMovie
        }
    }

    /**
     * Converts UI season value to API enum
     */
    fun convertSeasonToApi(season: String?): String? {
        return season?.let {
            when (it) {
                "Winter" -> "WINTER"
                "Spring" -> "SPRING"
                "Summer" -> "SUMMER"
                "Fall" -> "FALL"
                else -> null
            }
        }
    }

    /**
     * Converts API season enum to UI value
     */
    fun convertSeasonFromApi(season: String?): String {
        return season?.let {
            when (it) {
                "WINTER" -> "Winter"
                "SPRING" -> "Spring"
                "SUMMER" -> "Summer"
                "FALL" -> "Fall"
                else -> "All"
            }
        } ?: "All"
    }

    /**
     * Converts UI format value to API enum using AniListApi helper
     */
    fun convertFormatToApi(format: String?): String? {
        return if (format == "All") null else AniListApi.mapFormatToAniListEnum(format)
    }

    /**
     * Converts API format enum to UI value
     */
    fun convertFormatFromApi(format: String?): String {
        return format?.let {
            when (it) {
                "TV" -> "TV Show"
                "TV_SHORT" -> "TV Short"
                "MOVIE" -> "Movie"
                "SPECIAL" -> "Special"
                "OVA" -> "OVA"
                "ONA" -> "ONA"
                "MUSIC" -> "Music"
                else -> "All"
            }
        } ?: "All"
    }

    /**
     * Converts UI sort value to API enum
     */
    fun convertSortToApi(sort: String?): String? {
        return sort?.let {
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
    }

    /**
     * Converts API sort enum to UI value
     */
    fun convertSortFromApi(sort: String?): String {
        return sort?.let {
            when (it) {
                "POPULARITY_DESC" -> "Popularity"
                "SCORE_DESC" -> "Average Score"
                "TRENDING_DESC" -> "Trending"
                "FAVOURITES_DESC" -> "Favorites"
                "TITLE_ROMAJI" -> "Title"
                "ID_DESC" -> "Date Added"
                "START_DATE_DESC" -> "Release Date"
                else -> "Popularity"
            }
        } ?: "Popularity"
    }

    /**
     * RecyclerView adapter for AniList filter selections
     * Supports both checkbox (multi-select) and radio (single-select) modes
     */
    class AniListCheckboxAdapter(
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
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_anilist_radio_selection, parent, false)
                RadioViewHolder(view)
            } else {
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
