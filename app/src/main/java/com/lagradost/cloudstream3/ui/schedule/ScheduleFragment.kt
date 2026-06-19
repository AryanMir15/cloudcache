package com.lagradost.cloudstream3.ui.schedule

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentScheduleBinding
import com.lagradost.cloudstream3.databinding.ScheduleDayPillBinding
import com.lagradost.cloudstream3.databinding.ItemSchedulePosterBinding
import androidx.preference.PreferenceManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigationrail.NavigationRailView
import com.lagradost.cloudstream3.ui.AutofitRecyclerView
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import kotlin.math.roundToInt
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

class ScheduleFragment : Fragment() {

    private var _binding: FragmentScheduleBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ScheduleViewModel by viewModels()

    private var selectedDay: DayOfWeek = LocalDate.now().dayOfWeek
    private var allItems: List<WeeklyScheduleItem> = emptyList()
    private var daySelectorAdapter: DaySelectorAdapter? = null

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

        daySelectorAdapter = DaySelectorAdapter { day ->
            selectedDay = day
            updateContent(day)
            daySelectorAdapter?.selectDay(day)
            scrollToCenterDay(day)
        }

        binding.daySelectorRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = daySelectorAdapter
            clipToPadding = false
        }

        binding.daySelectorRecycler.post {
            centerDayPills()
        }

        viewModel.scheduleItems.observe(viewLifecycleOwner) { items ->
            allItems = items
            val nullItems = items.filter { it.scheduleName.isBlank() || it.scheduleName == "null" }
            android.util.Log.d("SCHEDULE_FRAGMENT", "Observer: ${items.size} items, ${nullItems.size} with null/blank names")
            nullItems.forEach { android.util.Log.w("SCHEDULE_FRAGMENT", "  null-name: id=${it.scheduleId} name=[${it.scheduleName}] url=${it.url}") }

            val counts = mutableMapOf<DayOfWeek, Int>()
            for (item in items) {
                val dow = Instant.ofEpochMilli(item.airingAt)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .dayOfWeek
                counts[dow] = (counts[dow] ?: 0) + 1
            }
            daySelectorAdapter?.updateCounts(counts)

            autoSelectDay()
            updateContent(selectedDay)
            scrollToCenterDay(selectedDay)
        }

        viewModel.loadSchedule()
    }

    private fun autoSelectDay() {
        val today = LocalDate.now().dayOfWeek
        val todayItems = allItems.filter { item ->
            Instant.ofEpochMilli(item.airingAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .dayOfWeek == today
        }

        if (todayItems.isNotEmpty()) {
            selectedDay = today
            return
        }

        val dayOrder = listOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
        )
        for (day in dayOrder) {
            if (allItems.any {
                    Instant.ofEpochMilli(it.airingAt)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                        .dayOfWeek == day
                }) {
                selectedDay = day
                return
            }
        }
    }

    private fun centerDayPills() {
        val llm = binding.daySelectorRecycler.layoutManager as? LinearLayoutManager ?: return
        val recyclerWidth = binding.daySelectorRecycler.width
        var totalItemsWidth = 0
        for (i in 0 until llm.itemCount) {
            val v = llm.findViewByPosition(i) ?: continue
            totalItemsWidth += v.width
        }
        val spacing = 8.toPx
        totalItemsWidth += spacing * (llm.itemCount - 1)
        if (totalItemsWidth < recyclerWidth) {
            val pad = (recyclerWidth - totalItemsWidth) / 2
            binding.daySelectorRecycler.setPadding(pad, 0, pad, 0)
        }
    }

    private fun scrollToCenterDay(day: DayOfWeek) {
        val days = listOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
        )
        val pos = days.indexOf(day)
        if (pos < 0) return

        binding.daySelectorRecycler.post {
            val layoutManager = binding.daySelectorRecycler.layoutManager as? LinearLayoutManager ?: return@post
            val view = layoutManager.findViewByPosition(pos) ?: return@post
            val viewWidth = view.width
            val recyclerWidth = binding.daySelectorRecycler.width
            val offset = (recyclerWidth - viewWidth) / 2
            val currentLeft = view.left
            binding.daySelectorRecycler.scrollBy(currentLeft - offset, 0)
        }
    }

    private fun updateContent(day: DayOfWeek) {
        val dayItems = allItems.filter { item ->
            Instant.ofEpochMilli(item.airingAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .dayOfWeek == day
        }

        val today = LocalDate.now().dayOfWeek
        val dayLabel = day.getDisplayName(TextStyle.FULL, Locale.getDefault())
        binding.scheduleDayTitle.text = if (day == today) {
            "Airing Today \u2022 $dayLabel"
        } else {
            "Airing on $dayLabel"
        }

        val hasItems = dayItems.isNotEmpty()
        binding.scheduleEmptyText.isVisible = !hasItems
        binding.scheduleDayTitle.isVisible = hasItems
        binding.schedulePosterRecycler.isVisible = hasItems

        val posterAdapter = SchedulePosterAdapter(binding.schedulePosterRecycler, dayItems) { item ->
            navigateToResult(item)
        }
        val posterScale = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getInt(requireContext().getString(R.string.poster_size_key), 5)
        val mul = 1.0f + posterScale * 0.1f
        val density = resources.displayMetrics.density
        val screenWidthDp = resources.displayMetrics.widthPixels / density
        val desiredItemWidthDp = 114 * mul
        val spanCount = (screenWidthDp / desiredItemWidthDp).toInt().coerceIn(2, 6)
        binding.schedulePosterRecycler.spanCount = spanCount
        binding.schedulePosterRecycler.adapter = posterAdapter
    }

    private fun navigateToResult(item: WeeklyScheduleItem) {
        val name = item.scheduleName ?: return
        com.lagradost.cloudstream3.MainActivity.nextSearchQuery = name
        val bottomNav = activity?.findViewById<BottomNavigationView>(R.id.nav_view)
        val navRail = activity?.findViewById<NavigationRailView>(R.id.nav_rail_view)
        bottomNav?.selectedItemId = R.id.navigation_search
        navRail?.selectedItemId = R.id.navigation_search
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// --- Day Selector ---

class DaySelectorAdapter(
    private val onDaySelected: (DayOfWeek) -> Unit
) : RecyclerView.Adapter<DaySelectorAdapter.DayPillViewHolder>() {

    private val days = listOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
    )

    private var selectedDay: DayOfWeek = LocalDate.now().dayOfWeek
    private val today: DayOfWeek = LocalDate.now().dayOfWeek
    private var itemCounts: Map<DayOfWeek, Int> = emptyMap()

    fun selectDay(day: DayOfWeek) {
        val oldPos = days.indexOf(selectedDay)
        val newPos = days.indexOf(day)
        selectedDay = day
        if (oldPos >= 0) notifyItemChanged(oldPos)
        if (newPos >= 0) notifyItemChanged(newPos)
    }

    fun updateCounts(counts: Map<DayOfWeek, Int>) {
        itemCounts = counts
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayPillViewHolder {
        val binding = ScheduleDayPillBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DayPillViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DayPillViewHolder, position: Int) {
        holder.bind(days[position])
    }

    override fun getItemCount() = days.size

    inner class DayPillViewHolder(
        private val binding: ScheduleDayPillBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(day: DayOfWeek) {
            val label = day.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase(Locale.getDefault())
            val count = itemCounts[day] ?: 0
            binding.dayPillText.text = if (count > 0) "$label \u00b7 $count" else label

            val isSelected = day == selectedDay
            val isToday = day == today

            val bgRes = when {
                isToday && isSelected -> R.drawable.schedule_pill_today
                isSelected -> R.drawable.schedule_pill_selected
                else -> 0
            }

            if (bgRes != 0) {
                binding.dayPillText.setBackgroundResource(bgRes)
            } else {
                binding.dayPillText.background = null
            }

            binding.dayPillText.setTextColor(
                ContextCompat.getColor(binding.root.context, android.R.color.white)
            )
            binding.dayPillText.typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

            binding.root.setOnClickListener {
                onDaySelected(day)
            }
        }
    }
}

// --- Poster Adapter ---

class SchedulePosterAdapter(
    private val resView: AutofitRecyclerView,
    private val items: List<WeeklyScheduleItem>,
    private val onItemClick: (WeeklyScheduleItem) -> Unit
) : RecyclerView.Adapter<SchedulePosterAdapter.PosterViewHolder>() {

    private val coverHeight: Int get() = (resView.itemWidth / 0.68).roundToInt()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PosterViewHolder {
        val binding = ItemSchedulePosterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PosterViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PosterViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class PosterViewHolder(
        private val binding: ItemSchedulePosterBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: WeeklyScheduleItem) {
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                coverHeight
            )
            if (params.height != binding.posterImage.layoutParams.height || params.width != binding.posterImage.layoutParams.width) {
                binding.posterImage.layoutParams = params
            }
            binding.posterImage.loadImage(item.posterUrl)
            binding.posterTitle.text = item.name
            if (item.episodeNumber != null) {
                binding.posterEpisode.isVisible = true
                binding.posterEpisode.text = "Ep ${item.episodeNumber}"
            } else {
                binding.posterEpisode.isVisible = false
            }

            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }
    }
}
