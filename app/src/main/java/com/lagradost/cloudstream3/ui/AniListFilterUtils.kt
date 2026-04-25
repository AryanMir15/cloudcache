package com.lagradost.cloudstream3.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R

object AniListFilterUtils {
    // Combined list of all AniList genres and tags
    // UI-only separation: genres (first 18 items) vs tags (rest)
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

    val GENRES = ALL_GENRES_AND_TAGS.take(18)
    val TAGS = ALL_GENRES_AND_TAGS.drop(18)

    // Hardcoded Years
    val YEARS = listOf("All") + (1940..2027).reversed().map { it.toString() }

    // Hardcoded Seasons
    val SEASONS = listOf("All", "Winter", "Spring", "Summer", "Fall")

    // Hardcoded Formats
    val FORMATS = listOf("All", "TV Show", "Movie", "TV Short", "Special", "OVA", "ONA", "Music")

    // Hardcoded Sort Options (display name -> API enum value)
    val SORT_OPTIONS = listOf(
        "Popularity" to "POPULARITY_DESC",
        "Average Score" to "SCORE_DESC",
        "Trending" to "TRENDING_DESC",
        "Favorites" to "FAVOURITES_DESC",
        "Title" to "TITLE_ROMAJI",
        "Date Added" to "ID_DESC",
        "Release Date" to "START_DATE_DESC"
    ).map { it.first }

    // Conversion functions
    fun convertSeasonToApi(season: String): String? {
        return if (season == "All") null else when (season) {
            "Winter" -> "WINTER"
            "Spring" -> "SPRING"
            "Summer" -> "SUMMER"
            "Fall" -> "FALL"
            else -> null
        }
    }

    fun convertFormatToApi(format: String): String? {
        return if (format == "All") null else when (format) {
            "TV Show" -> "TV"
            "Movie" -> "MOVIE"
            "TV Short" -> "TV_SHORT"
            "Special" -> "SPECIAL"
            "OVA" -> "OVA"
            "ONA" -> "ONA"
            "Music" -> "MUSIC"
            else -> null
        }
    }

    fun convertSortToApi(sort: String): String? {
        return if (sort == "All") null else when (sort) {
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

    // Adapter for checkbox/radio selection
    class AniListCheckboxAdapter(
        private val items: List<String>,
        private var selectedItems: Set<String>,
        private var excludedItems: Set<String> = emptySet(),
        private val onCheckedChangeListener: (String, Int) -> Unit, // 0 = unchecked, 1 = include, 2 = exclude
        private val radioMode: Boolean = false
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        class CheckboxViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val checkbox: CheckBox = view.findViewById(R.id.checkbox)
            val crossIcon: ImageView = view.findViewById(R.id.cross_icon)
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

        fun updateExcludedSet(newSet: Set<String>) {
            excludedItems = newSet
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
            val isExcluded = excludedItems.contains(item)

            android.util.Log.d("CheckboxSizeDebug", "onBindViewHolder: item=$item, isSelected=$isSelected, isExcluded=$isExcluded")

            if (holder is CheckboxViewHolder) {
                holder.text.text = item

                // Log dimensions
                holder.checkbox.post {
                    android.util.Log.d("CheckboxSizeDebug", "Checkbox: width=${holder.checkbox.width}, height=${holder.checkbox.height}, measuredWidth=${holder.checkbox.measuredWidth}, measuredHeight=${holder.checkbox.measuredHeight}")
                }
                holder.crossIcon.post {
                    android.util.Log.d("CheckboxSizeDebug", "CrossIcon: width=${holder.crossIcon.width}, height=${holder.crossIcon.height}, measuredWidth=${holder.crossIcon.measuredWidth}, measuredHeight=${holder.crossIcon.measuredHeight}")
                    val drawable = holder.crossIcon.drawable
                    android.util.Log.d("CheckboxSizeDebug", "CrossIcon drawable: intrinsicWidth=${drawable?.intrinsicWidth}, intrinsicHeight=${drawable?.intrinsicHeight}, bounds=${drawable?.bounds}")
                }

                // Set checkbox state
                holder.checkbox.isChecked = isSelected

                // Toggle visibility based on state
                if (isExcluded) {
                    holder.checkbox.visibility = View.GONE
                    holder.crossIcon.visibility = View.VISIBLE
                    android.util.Log.d("CheckboxSizeDebug", "Show cross icon, hide checkbox for item=$item")
                } else {
                    holder.checkbox.visibility = View.VISIBLE
                    holder.crossIcon.visibility = View.GONE
                    android.util.Log.d("CheckboxSizeDebug", "Show checkbox, hide cross icon for item=$item, checked=$isSelected")
                }

                // Remove checkbox's own listener to prevent conflicts
                holder.checkbox.setOnCheckedChangeListener(null)

                holder.itemView.setOnClickListener {
                    android.util.Log.d("CheckboxSizeDebug", "onClick: item=$item, isSelected=$isSelected, isExcluded=$isExcluded")
                    // Cycle through states: 0 -> 1 -> 2 -> 0
                    val currentState = if (isExcluded) 2 else if (isSelected) 1 else 0
                    val newState = when (currentState) {
                        0 -> 1 // unchecked -> include
                        1 -> 2 // include -> exclude
                        2 -> 0 // exclude -> unchecked
                        else -> 1
                    }
                    android.util.Log.d("CheckboxSizeDebug", "onClick: currentState=$currentState, newState=$newState")
                    onCheckedChangeListener(item, newState)
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
                    onCheckedChangeListener(item, 1)
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
