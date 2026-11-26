package com.lagradost.cloudstream3.ui.schedule

import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.chip.Chip
import com.google.android.material.navigationrail.NavigationRailView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.databinding.FragmentScheduleBinding
import com.lagradost.cloudstream3.databinding.ItemScheduleCardBinding
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.getSpanCount
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

class ScheduleFragment : Fragment() {

    private var _binding: FragmentScheduleBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ScheduleViewModel by viewModels()

    private var selectedDay: DayOfWeek = LocalDate.now().dayOfWeek
    private var didAutoSelectDay = false
    private var cardAdapter: ScheduleCardAdapter? = null
    private var currentStyle: ScheduleViewStyle = ScheduleViewStyle.LIST

    private lateinit var dayPillMap: Map<DayOfWeek, Chip>
    private var themeColor: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScheduleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        themeColor = requireContext().colorFromAttribute(R.attr.colorPrimary)

        fixSystemBarsPadding(view)

        dayPillMap = mapOf(
            DayOfWeek.MONDAY to binding.dayMon,
            DayOfWeek.TUESDAY to binding.dayTue,
            DayOfWeek.WEDNESDAY to binding.dayWed,
            DayOfWeek.THURSDAY to binding.dayThu,
            DayOfWeek.FRIDAY to binding.dayFri,
            DayOfWeek.SATURDAY to binding.daySat,
            DayOfWeek.SUNDAY to binding.daySun
        )

        dayPillMap.forEach { (day, chip) ->
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    // Uncheck all other chips
                    dayPillMap.values.forEach { other ->
                        if (other != chip) other.isChecked = false
                    }
                    onDaySelected(day)
                } else if (dayPillMap.values.none { it.isChecked }) {
                    // Prevent unchecking the last one — re-check this
                    chip.isChecked = true
                }
            }
        }

        // Set initial checked state for today or first day with items
        // (will be properly set in autoSelectDay / updateContent)

        binding.scheduleToolbar.inflateMenu(R.menu.schedule_menu)
        binding.scheduleToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_filter_schedule -> {
                    showFilterDialog()
                    true
                }
                else -> false
            }
        }

        cardAdapter = ScheduleCardAdapter { item ->
            navigateToResult(item)
        }.also { it.showLogos = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getBoolean("schedule_show_logos", true) }
        binding.scheduleRecycler.apply {
            adapter = cardAdapter
            // Swipe left/right to hop between days (the day pills are tiny on
            // phones, so a horizontal fling over the list changes the day).
            setOnTouchListener { _, event ->
                swipeDetector.onTouchEvent(event)
                false // keep vertical scrolling on the RecyclerView
            }
        }
        // Honor the saved card style (list/grid) from the filter dialog
        val savedStyle = if (PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getString("schedule_view_style", "list") == "grid"
        ) ScheduleViewStyle.GRID else ScheduleViewStyle.LIST
        applyViewStyle(savedStyle)

        viewModel.scheduleItems.observe(viewLifecycleOwner) { items ->
            Log.d(TAG, "════════ scheduleItems EMITTED: ${items.size} total items ════════")
            items.forEachIndexed { i, it ->
                Log.d(TAG, "  [$i] title=[${it.scheduleName}] type=${it.scheduleType} ep=${it.episodeNumber} " +
                    "airing=${Instant.ofEpochMilli(it.airingAt).atZone(ZoneId.systemDefault()).toLocalDate().dayOfWeek} " +
                    "banner=${shortUrl(it.scheduleBannerUrl)} poster=${shortUrl(it.schedulePosterUrl)} logo=${shortUrl(it.scheduleLogoUrl)}")
            }
            // Only auto-pick a day on the first non-empty emission — a later
            // background refresh must not yank the user's manual day selection.
            if (!didAutoSelectDay && items.isNotEmpty()) {
                didAutoSelectDay = true
                autoSelectDay(items)
            }
            updateContent(selectedDay)
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            Log.d(TAG, "isLoading = $loading")
            binding.scheduleProgress.isVisible = loading
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            if (error != null) Log.w(TAG, "errorMessage = [$error]")
            binding.scheduleErrorText.isVisible = error != null
            binding.scheduleErrorText.text = error ?: ""
        }

        viewModel.statusMessage.observe(viewLifecycleOwner) { msg ->
            Log.d(TAG, "statusMessage = [${msg ?: "(cleared)"}]")
            if (!msg.isNullOrBlank()) {
                binding.scheduleStatusBar.isVisible = true
                binding.scheduleStatusBar.text = msg
            } else {
                binding.scheduleStatusBar.isVisible = false
            }
        }

        Log.d(TAG, "onViewCreated done → calling loadSchedule()")
        viewModel.loadSchedule()
    }

    private fun autoSelectDay(items: List<WeeklyScheduleItem>) {
        val today = LocalDate.now().dayOfWeek
        val todayItems = items.filter { item ->
            Instant.ofEpochMilli(item.airingAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .dayOfWeek == today
        }

        if (todayItems.isNotEmpty()) {
            selectedDay = today
            Log.d(TAG, "autoSelectDay → TODAY ($today) has ${todayItems.size} items")
            return
        }

        val dayOrder = listOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
        )
        for (day in dayOrder) {
            if (items.any {
                    Instant.ofEpochMilli(it.airingAt)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                        .dayOfWeek == day
                }) {
                selectedDay = day
                Log.d(TAG, "autoSelectDay → first non-empty day = $day")
                return
            }
        }
        Log.d(TAG, "autoSelectDay → no items on any day, keeping selectedDay=$selectedDay")
    }

    private fun dayItems(day: DayOfWeek): List<WeeklyScheduleItem> {
        val allItems = viewModel.scheduleItems.value ?: return emptyList()
        return allItems.filter { item ->
            Instant.ofEpochMilli(item.airingAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .dayOfWeek == day
        }
            // One entry per show per day, no matter how many episodes air
            .distinctBy { it.scheduleId }
            .sortedBy { it.airingAt }
    }

    private fun updateContent(day: DayOfWeek) {
        val dayItems = dayItems(day)
        Log.d(TAG, "updateContent(day=$day) → ${dayItems.size} items to render: ${dayItems.map { it.scheduleName }}")
        binding.scheduleEmptyText.isVisible = dayItems.isEmpty() && viewModel.errorMessage.value == null
        binding.scheduleRecycler.isVisible = dayItems.isNotEmpty()

        submitDayItems(dayItems)
        highlightDayPill(day)
    }

    /** Feeds the active adapter (banner list vs global poster grid). */
    private fun submitDayItems(dayItems: List<WeeklyScheduleItem>) {
        if (currentStyle == ScheduleViewStyle.GRID) {
            gridAdapter?.submitList(dayItems.map { it.toSearchResponse() })
        } else {
            cardAdapter?.submitList(dayItems)
        }
    }

    /** SearchResponse view of a schedule item for the global grid cards. */
    private fun WeeklyScheduleItem.toSearchResponse(): com.lagradost.cloudstream3.SearchResponse {
        @Suppress("DEPRECATION_ERROR")
        return com.lagradost.cloudstream3.MovieSearchResponse(
            scheduleName,
            "",
            "Schedule",
            if (scheduleType == ScheduleType.TV) TvType.TvSeries else TvType.Anime,
            schedulePosterUrl ?: scheduleBannerUrl,
            null,
            scheduleId
        )
    }

    private fun onDaySelected(day: DayOfWeek) {
        if (day == selectedDay) {
            Log.d(TAG, "TAB CHANGE ignored → tapped already-selected day $day")
            highlightDayPill(day)
            return
        }
        Log.d(TAG, "──────── TAB CHANGE: $selectedDay → $day ────────")
        selectedDay = day

        val dayItems = dayItems(day)
        Log.d(TAG, "TAB CHANGE → day=$day has ${dayItems.size} items: ${dayItems.map { it.scheduleName }}")
        binding.scheduleEmptyText.isVisible = dayItems.isEmpty() && viewModel.errorMessage.value == null
        binding.scheduleRecycler.isVisible = dayItems.isNotEmpty()

        submitDayItems(dayItems)
        binding.scheduleRecycler.scrollToPosition(0)
        highlightDayPill(day)
    }

    private fun highlightDayPill(selected: DayOfWeek) {
        val density = resources.displayMetrics.density
        dayPillMap.forEach { (day, chip) ->
            val isSelected = day == selected

            // Chip handles selected/unselected via chip_color / chip_color_text selectors
            chip.isChecked = isSelected

            // The row is one line on phones: keep unselected days slim and
            // subtle, and let the selected day pop with a fuller pill.
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isSelected) 15f else 12f)
            chip.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            chip.chipMinHeight = (if (isSelected) 42f else 32f) * density
            chip.alpha = if (isSelected) 1f else 0.55f
        }
    }

    /** Selects [day] by checking its pill — reuses the existing listener path. */
    private fun selectDay(day: DayOfWeek) {
        dayPillMap[day]?.isChecked = true
    }

    private val swipeDetector by lazy {
        GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (e1 == null) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (abs(dx) > abs(dy) && abs(dx) > 100f && abs(velocityX) > 300f) {
                        Log.d(TAG, "SWIPE → ${if (velocityX < 0) "next" else "prev"} day (dx=$dx)")
                        if (velocityX < 0) selectDay(selectedDay.plus(1))
                        else selectDay(selectedDay.minus(1))
                        return true
                    }
                    return false
                }
            }
        )
    }

    private fun navigateToResult(item: WeeklyScheduleItem) {
        navigateToResultName(item.scheduleName)
    }

    private fun showFilterDialog() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val currentValue = prefs.getBoolean("schedule_include_tv_shows", false)
        val currentStyle = if (prefs.getString("schedule_view_style", "list") == "grid") {
            ScheduleViewStyle.GRID
        } else {
            ScheduleViewStyle.LIST
        }

        val checkBox = androidx.appcompat.widget.AppCompatCheckBox(requireContext()).apply {
            text = getString(R.string.schedule_include_tv_shows)
            isChecked = currentValue
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("schedule_include_tv_shows", isChecked).apply()
                viewModel.setIncludeTvShows(isChecked)
            }
            setPadding(24, 24, 24, 24)
        }

        fun makeCheckBox(
            label: String,
            key: String,
            onToggle: (Boolean) -> Unit
        ) = androidx.appcompat.widget.AppCompatCheckBox(requireContext()).apply {
            text = label
            isChecked = prefs.getBoolean(key, false)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(key, isChecked).apply()
                onToggle(isChecked)
            }
            setPadding(24, 4, 24, 4)
        }

        val ecchiBox = makeCheckBox(
            getString(R.string.schedule_exclude_ecchi),
            "schedule_exclude_ecchi"
        ) { viewModel.setExcludeEcchi(it) }

        val premieresBox = makeCheckBox(
            getString(R.string.schedule_premieres_only),
            "schedule_premieres_only"
        ) { viewModel.setPremieresOnly(it) }

        val logosBox = makeCheckBox(
            getString(R.string.schedule_show_logos),
            "schedule_show_logos"
        ) { cardAdapter?.showLogos = it }

        // Card style: full-width rows or a poster grid — two small side-by-side
        // buttons, the active one tinted with the accent color.
        var selectedStyle = currentStyle
        val accent = requireContext().colorFromAttribute(R.attr.colorPrimary)
        val textColor = requireContext().colorFromAttribute(R.attr.textColor)

        fun Int.dpPx(): Int = (this * resources.displayMetrics.density).toInt()

        val listButton = com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = getString(R.string.schedule_view_style_list)
            isAllCaps = false
            layoutParams = android.widget.LinearLayout.LayoutParams(
                88.dpPx(), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 8.dpPx() }
        }
        val gridButton = com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = getString(R.string.schedule_view_style_grid)
            isAllCaps = false
            layoutParams = android.widget.LinearLayout.LayoutParams(
                88.dpPx(), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val styleRow = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(24, 8, 24, 8)
            addView(listButton)
            addView(gridButton)
        }

        fun paintStyleButtons() {
            fun paint(btn: com.google.android.material.button.MaterialButton, active: Boolean) {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (active) accent else android.graphics.Color.TRANSPARENT
                )
                btn.setTextColor(if (active) android.graphics.Color.WHITE else textColor)
                btn.strokeColor = android.content.res.ColorStateList.valueOf(
                    if (active) accent else textColor
                )
                btn.strokeWidth = 1.dpPx()
            }
            paint(listButton, selectedStyle == ScheduleViewStyle.LIST)
            paint(gridButton, selectedStyle == ScheduleViewStyle.GRID)
        }

        listButton.setOnClickListener {
            selectedStyle = ScheduleViewStyle.LIST
            paintStyleButtons()
        }
        gridButton.setOnClickListener {
            selectedStyle = ScheduleViewStyle.GRID
            paintStyleButtons()
        }

        val styleLabel = android.widget.TextView(requireContext()).apply {
            text = getString(R.string.schedule_view_style)
            setPadding(24, 16, 24, 0)
            setTextColor(textColor)
            textSize = 14f
        }

        // Refresh moved into this modal (was the toolbar icon)
        val refreshButton = com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = getString(R.string.refresh)
            isAllCaps = false
            setPadding(24, 8, 24, 8)
        }

        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(checkBox)
            addView(ecchiBox)
            addView(premieresBox)
            addView(logosBox)
            addView(styleLabel)
            addView(styleRow)
            addView(refreshButton)
        }
        paintStyleButtons()

        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext(), R.style.AlertDialogCustom)
            .setTitle(R.string.filter)
            .setView(container)
            .setPositiveButton(R.string.apply) { _, _ ->
                prefs.edit().putString(
                    "schedule_view_style",
                    if (selectedStyle == ScheduleViewStyle.GRID) "grid" else "list"
                ).apply()
                applyViewStyle(selectedStyle)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        refreshButton.setOnClickListener {
            dialog.dismiss()
            Log.d(TAG, "──────── MANUAL REFRESH triggered → clearing caches + fresh fetch ────────")
            viewModel.forceRefresh()
        }
        dialog.show()
    }

    private fun applyViewStyle(style: ScheduleViewStyle) {
        Log.d(TAG, "applyViewStyle → $style")
        currentStyle = style
        val recycler = binding.scheduleRecycler
        (recycler as? com.lagradost.cloudstream3.ui.AutofitRecyclerView)?.spanCount =
            if (style == ScheduleViewStyle.GRID) requireContext().getSpanCount() else 1
        if (style == ScheduleViewStyle.GRID) {
            if (recycler.adapter !is SearchAdapter) {
                recycler.adapter = gridAdapter
            }
            viewModel.scheduleItems.value?.let { submitDayItems(it) }
        } else {
            recycler.adapter = cardAdapter
            viewModel.scheduleItems.value?.let { submitDayItems(it) }
        }
        recycler.scrollToPosition(0)
    }

    /** Global-style poster grid (SearchAdapter) fed with the day's items. */
    private val gridAdapter: SearchAdapter by lazy {
        SearchAdapter(
            binding.scheduleRecycler as com.lagradost.cloudstream3.ui.AutofitRecyclerView,
            isHorizontal = false
        ) { callback ->
            navigateToResultName(callback.card.name)
        }
    }

    private fun navigateToResultName(name: String?) {
        if (name.isNullOrBlank()) return
        com.lagradost.cloudstream3.MainActivity.nextSearchQuery = name
        val bottomNav = activity?.findViewById<BottomNavigationView>(R.id.nav_view)
        val navRail = activity?.findViewById<NavigationRailView>(R.id.nav_rail_view)
        bottomNav?.selectedItemId = R.id.navigation_search
        navRail?.selectedItemId = R.id.navigation_search
    }

    override fun onDestroyView() {
        Log.d(TAG, "onDestroyView")
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SCHEDULE_LOGS"

        fun shortUrl(url: String?): String {
            if (url.isNullOrBlank()) return "∅"
            return url.substringAfterLast('/').take(24)
        }
    }
}

// --- Card Adapter with DiffUtil for stable tab switching ---

/** How schedule cards render: full-width banner rows or a poster grid. */
enum class ScheduleViewStyle { LIST, GRID }

class ScheduleCardAdapter(
    private val onItemClick: (WeeklyScheduleItem) -> Unit
) : RecyclerView.Adapter<ScheduleCardAdapter.CardViewHolder>() {

    /** When true, show the logo image instead of the title where available. */
    var showLogos: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    var style: ScheduleViewStyle = ScheduleViewStyle.LIST
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    private var items: List<WeeklyScheduleItem> = emptyList()

    fun submitList(list: List<WeeklyScheduleItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = style.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        val layout = if (viewType == ScheduleViewStyle.GRID.ordinal) {
            R.layout.item_schedule_grid_card
        } else {
            R.layout.item_schedule_card
        }
        val root = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return CardViewHolder(root)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    inner class CardViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

        private val card: View = root.findViewById(R.id.schedule_card)
        private val posterBg: ImageView = root.findViewById(R.id.schedule_poster_bg)
        private val logo: ImageView = root.findViewById(R.id.schedule_logo)
        private val time: TextView = root.findViewById(R.id.schedule_time)
        private val episode: TextView = root.findViewById(R.id.schedule_episode)
        private val name: TextView = root.findViewById(R.id.schedule_name)
        private val watchBtn: View = root.findViewById(R.id.schedule_watch_btn)

        fun bind(item: WeeklyScheduleItem, position: Int) {
            Log.d(ScheduleFragment.TAG, "bind[pos=$position] CONTAINER=[${item.scheduleName}] ← " +
                "banner=${ScheduleFragment.shortUrl(item.scheduleBannerUrl)} " +
                "poster=${ScheduleFragment.shortUrl(item.schedulePosterUrl)} " +
                "logo=${ScheduleFragment.shortUrl(item.scheduleLogoUrl)}")
            posterBg.setImageDrawable(null)
            logo.setImageDrawable(null)

            val airTime = Instant.ofEpochMilli(item.airingAt)
                .atZone(ZoneId.systemDefault())
            time.text = airTime.format(timeFormatter)

            if (item.episodeNumber != null) {
                episode.isVisible = true
                episode.text = "Episode ${item.episodeNumber}"
            } else {
                episode.isVisible = false
            }

            val logoUrl = item.scheduleLogoUrl
            if (showLogos && !logoUrl.isNullOrBlank() && logoUrl.startsWith("http")) {
                Log.d(ScheduleFragment.TAG, "  → [${item.scheduleName}] showing LOGO (title text hidden): ${ScheduleFragment.shortUrl(logoUrl)}")
                logo.isVisible = true
                name.isVisible = false
                // error(null) ensures a failed/404 load leaves no ghost of the previous image
                logo.loadImage(logoUrl) {
                    placeholder(null)
                    error(null)
                }
            } else {
                Log.d(ScheduleFragment.TAG, "  → [${item.scheduleName}] showing TITLE TEXT (no logo)")
                logo.isVisible = false
                name.isVisible = true
                name.text = item.scheduleName
            }

            val imageUrl = item.scheduleBannerUrl ?: item.schedulePosterUrl
            val bannerSource = when {
                !item.scheduleBannerUrl.isNullOrBlank() -> "BANNER"
                !item.schedulePosterUrl.isNullOrBlank() -> "POSTER(fallback)"
                else -> "NONE"
            }
            if (!imageUrl.isNullOrBlank() && imageUrl.startsWith("http")) {
                Log.d(ScheduleFragment.TAG, "  → [${item.scheduleName}] background image=$bannerSource: ${ScheduleFragment.shortUrl(imageUrl)}")
                // error(null) ensures a failed/404 load leaves no ghost of the previous image
                posterBg.loadImage(imageUrl) {
                    placeholder(null)
                    error(null)
                }
            } else {
                Log.w(ScheduleFragment.TAG, "  → [${item.scheduleName}] NO background image (banner+poster both empty) → card will be blank")
            }

            watchBtn.setOnClickListener {
                onItemClick(item)
            }

            card.setOnClickListener {
                onItemClick(item)
            }
        }
    }
}
