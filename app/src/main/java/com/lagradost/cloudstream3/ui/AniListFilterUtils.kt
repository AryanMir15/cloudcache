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
    val FORMATS = listOf("All", "TV Show", "Movie", "TV Short", "Special", "OVA", "ONA")

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

        // Track previous states to detect actual changes for animation
        private val previousStates = mutableMapOf<String, Int>() // item -> state (0=unchecked, 1=include, 2=exclude)
        
        // Track ongoing animations to cancel them when new state changes occur
        private val ongoingAnimations = mutableMapOf<String, Boolean>() // item -> isAnimating

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
            android.util.Log.d("MEMORY_LEAK_FIX", "updateSelectedSet: previousStates size=${previousStates.size}, newSet size=${newSet.size}")
            selectedItems = newSet
            notifyDataSetChanged()
        }

        fun updateExcludedSet(newSet: Set<String>) {
            android.util.Log.d("MEMORY_LEAK_FIX", "updateExcludedSet: previousStates size=${previousStates.size}, newSet size=${newSet.size}")
            excludedItems = newSet
            notifyDataSetChanged()
        }

        fun updateSingleItem(item: String, state: Int) {
            android.util.Log.d("SINGLE_ITEM_UPDATE", "updateSingleItem: item=$item, state=$state")
            // Don't update previousStates here - let onBindViewHolder handle it naturally
            // This ensures animations trigger correctly when state actually changes
            val position = items.indexOf(item)
            if (position >= 0) {
                android.util.Log.d("SINGLE_ITEM_UPDATE", "Notifying item changed at position=$position")
                notifyItemChanged(position)
            }
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

            // Determine current state
            val currentState = when {
                isExcluded -> 2
                isSelected -> 1
                else -> 0
            }

            // Get previous state (default to -1 if not set)
            val previousState = previousStates[item] ?: -1

            android.util.Log.d("CHECKBOX_ANIMATION_DEBUG", "onBindViewHolder: item=$item, currentState=$currentState, previousState=$previousState, stateChanged=${currentState != previousState}")

            if (holder is CheckboxViewHolder) {
                holder.text.text = item

                // Set checkbox state
                holder.checkbox.isChecked = isSelected

                // Only animate if state actually changed
                val shouldAnimate = currentState != previousState

                // Toggle visibility based on state with smooth fade animation
                if (isExcluded) {
                    if (shouldAnimate) {
                        android.util.Log.d("CHECKBOX_ANIMATION_DEBUG", "Animating to cross icon for item=$item")
                        // Cancel any ongoing animations for this item
                        if (ongoingAnimations[item] == true) {
                            android.util.Log.d("ANIMATION_RACE_FIX", "Cancelling ongoing animation for item=$item")
                            holder.checkbox.animate().cancel()
                            holder.crossIcon.animate().cancel()
                        }
                        ongoingAnimations[item] = true
                        
                        // Fade out checkbox, fade in cross icon with scale
                        holder.checkbox.animate()
                            .alpha(0f)
                            .setDuration(240)
                            .withEndAction {
                                holder.checkbox.visibility = View.GONE
                                holder.checkbox.alpha = 1f
                            }
                            .start()
                        holder.crossIcon.alpha = 0f
                        holder.crossIcon.scaleX = 0.5f
                        holder.crossIcon.scaleY = 0.5f
                        holder.crossIcon.visibility = View.VISIBLE
                        holder.crossIcon.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(240)
                            .withEndAction {
                                ongoingAnimations[item] = false
                                android.util.Log.d("ANIMATION_RACE_FIX", "Animation completed for item=$item")
                            }
                            .start()
                    } else {
                        // No animation, just set visibility
                        holder.checkbox.visibility = View.GONE
                        holder.crossIcon.visibility = View.VISIBLE
                        holder.checkbox.alpha = 1f
                        holder.crossIcon.alpha = 1f
                        holder.crossIcon.scaleX = 1f
                        holder.crossIcon.scaleY = 1f
                        ongoingAnimations[item] = false
                    }
                } else if (isSelected) {
                    if (shouldAnimate) {
                        android.util.Log.d("CHECKBOX_ANIMATION_DEBUG", "Animating to checked checkbox for item=$item")
                        // Cancel any ongoing animations for this item
                        if (ongoingAnimations[item] == true) {
                            android.util.Log.d("ANIMATION_RACE_FIX", "Cancelling ongoing animation for item=$item")
                            holder.checkbox.animate().cancel()
                            holder.crossIcon.animate().cancel()
                        }
                        ongoingAnimations[item] = true
                        
                        // Fade out cross icon with scale, fade in checkbox with scale
                        holder.crossIcon.animate()
                            .alpha(0f)
                            .scaleX(0.5f)
                            .scaleY(0.5f)
                            .setDuration(240)
                            .withEndAction {
                                holder.crossIcon.visibility = View.GONE
                                holder.crossIcon.alpha = 1f
                                holder.crossIcon.scaleX = 1f
                                holder.crossIcon.scaleY = 1f
                            }
                            .start()
                        holder.checkbox.alpha = 0f
                        holder.checkbox.scaleX = 0.8f
                        holder.checkbox.scaleY = 0.8f
                        holder.checkbox.visibility = View.VISIBLE
                        holder.checkbox.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(240)
                            .withEndAction {
                                ongoingAnimations[item] = false
                                android.util.Log.d("ANIMATION_RACE_FIX", "Animation completed for item=$item")
                            }
                            .start()
                    } else {
                        // No animation, just set visibility
                        holder.crossIcon.visibility = View.GONE
                        holder.checkbox.visibility = View.VISIBLE
                        holder.checkbox.alpha = 1f
                        holder.crossIcon.alpha = 1f
                        holder.crossIcon.scaleX = 1f
                        holder.crossIcon.scaleY = 1f
                        ongoingAnimations[item] = false
                    }
                } else {
                    // Unchecked state - animate checkbox with bounce effect
                    if (shouldAnimate) {
                        android.util.Log.d("CHECKBOX_ANIMATION_DEBUG", "Animating to unchecked for item=$item, starting animation")
                        // Cancel any ongoing animations for this item
                        if (ongoingAnimations[item] == true) {
                            android.util.Log.d("ANIMATION_RACE_FIX", "Cancelling ongoing animation for item=$item")
                            holder.checkbox.animate().cancel()
                            holder.crossIcon.animate().cancel()
                        }
                        ongoingAnimations[item] = true
                        
                        // Fade out cross icon if visible, animate checkbox with bounce
                        holder.crossIcon.animate()
                            .alpha(0f)
                            .scaleX(0.5f)
                            .scaleY(0.5f)
                            .setDuration(240)
                            .withEndAction {
                                holder.crossIcon.visibility = View.GONE
                                holder.crossIcon.alpha = 1f
                                holder.crossIcon.scaleX = 1f
                                holder.crossIcon.scaleY = 1f
                            }
                            .start()
                        
                        // Start checkbox at normal state, then bounce
                        holder.checkbox.scaleX = 1f
                        holder.checkbox.scaleY = 1f
                        holder.checkbox.alpha = 1f
                        holder.checkbox.visibility = View.VISIBLE
                        
                        // Bounce animation: scale down dramatically then back up
                        holder.checkbox.animate()
                            .scaleX(0.5f)
                            .scaleY(0.5f)
                            .alpha(0.3f)
                            .setDuration(160)
                            .withEndAction {
                                android.util.Log.d("CHECKBOX_ANIMATION_DEBUG", "Bounce down complete for item=$item")
                                holder.checkbox.animate()
                                    .scaleX(1.1f)
                                    .scaleY(1.1f)
                                    .alpha(1f)
                                    .setDuration(120)
                                    .withEndAction {
                                        android.util.Log.d("CHECKBOX_ANIMATION_DEBUG", "Bounce up complete for item=$item")
                                        holder.checkbox.animate()
                                            .scaleX(1f)
                                            .scaleY(1f)
                                            .setDuration(80)
                                            .withEndAction {
                                                ongoingAnimations[item] = false
                                                android.util.Log.d("ANIMATION_RACE_FIX", "Animation completed for item=$item")
                                            }
                                            .start()
                                    }
                                    .start()
                            }
                            .start()
                    } else {
                        // No animation, just set visibility
                        android.util.Log.d("CHECKBOX_ANIMATION_DEBUG", "No animation for unchecked item=$item")
                        holder.crossIcon.visibility = View.GONE
                        holder.checkbox.visibility = View.VISIBLE
                        holder.checkbox.alpha = 1f
                        holder.crossIcon.alpha = 1f
                        holder.crossIcon.scaleX = 1f
                        holder.crossIcon.scaleY = 1f
                        ongoingAnimations[item] = false
                    }
                }

                // Update previous state
                previousStates[item] = currentState

                // Remove checkbox's own listener to prevent conflicts
                holder.checkbox.setOnCheckedChangeListener(null)

                holder.itemView.setOnClickListener {
                    android.util.Log.d("CheckboxSizeDebug", "onClick: item=$item, isSelected=$isSelected, isExcluded=$isExcluded")
                    // Cycle through states: 0 -> 1 -> 2 -> 0
                    val clickCurrentState = if (isExcluded) 2 else if (isSelected) 1 else 0
                    val newState = when (clickCurrentState) {
                        0 -> 1 // unchecked -> include
                        1 -> 2 // include -> exclude
                        2 -> 0 // exclude -> unchecked
                        else -> 1
                    }
                    android.util.Log.d("CheckboxSizeDebug", "onClick: currentState=$clickCurrentState, newState=$newState")
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
