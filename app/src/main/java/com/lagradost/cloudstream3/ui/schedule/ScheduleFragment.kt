package com.lagradost.cloudstream3.ui.schedule

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigationrail.NavigationRailView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentScheduleBinding
import com.lagradost.cloudstream3.databinding.ItemScheduleCardBinding
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class ScheduleFragment : Fragment() {

    private var _binding: FragmentScheduleBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ScheduleViewModel by viewModels()

    private var selectedDay: DayOfWeek = LocalDate.now().dayOfWeek
    private var cardAdapter: ScheduleCardAdapter? = null

    private lateinit var dayPillMap: Map<DayOfWeek, TextView>
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

        dayPillMap = mapOf(
            DayOfWeek.MONDAY to binding.dayMon,
            DayOfWeek.TUESDAY to binding.dayTue,
            DayOfWeek.WEDNESDAY to binding.dayWed,
            DayOfWeek.THURSDAY to binding.dayThu,
            DayOfWeek.FRIDAY to binding.dayFri,
            DayOfWeek.SATURDAY to binding.daySat,
            DayOfWeek.SUNDAY to binding.daySun
        )

        dayPillMap.forEach { (day, pill) ->
            pill.setOnClickListener {
                onDaySelected(day)
            }
        }

        cardAdapter = ScheduleCardAdapter { item ->
            navigateToResult(item)
        }
        binding.scheduleRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = cardAdapter
        }

        viewModel.scheduleItems.observe(viewLifecycleOwner) { items ->
            autoSelectDay(items)
            updateContent(selectedDay)
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            binding.scheduleProgress.isVisible = loading
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            binding.scheduleErrorText.isVisible = error != null
            binding.scheduleErrorText.text = error ?: ""
        }

        viewModel.statusMessage.observe(viewLifecycleOwner) { msg ->
            if (!msg.isNullOrBlank()) {
                binding.scheduleStatusBar.isVisible = true
                binding.scheduleStatusBar.text = msg
            } else {
                binding.scheduleStatusBar.isVisible = false
            }
        }

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
                return
            }
        }
    }

    private fun dayItems(day: DayOfWeek): List<WeeklyScheduleItem> {
        val allItems = viewModel.scheduleItems.value ?: return emptyList()
        return allItems.filter { item ->
            Instant.ofEpochMilli(item.airingAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .dayOfWeek == day
        }.sortedBy { it.airingAt }
    }

    private fun updateContent(day: DayOfWeek) {
        val dayItems = dayItems(day)
        binding.scheduleEmptyText.isVisible = dayItems.isEmpty() && viewModel.errorMessage.value == null
        binding.scheduleRecycler.isVisible = dayItems.isNotEmpty()

        cardAdapter?.submitList(dayItems)
        highlightDayPill(day)
    }

    private fun onDaySelected(day: DayOfWeek) {
        if (day == selectedDay) {
            highlightDayPill(day)
            return
        }
        selectedDay = day

        val dayItems = dayItems(day)
        binding.scheduleEmptyText.isVisible = dayItems.isEmpty() && viewModel.errorMessage.value == null
        binding.scheduleRecycler.isVisible = dayItems.isNotEmpty()

        cardAdapter?.submitList(dayItems)
        binding.scheduleRecycler.scrollToPosition(0)
        highlightDayPill(day)
    }

    private fun highlightDayPill(selected: DayOfWeek) {
        val today = LocalDate.now().dayOfWeek
        dayPillMap.forEach { (day, pill) ->
            val isToday = day == today
            val isSelected = day == selected

            when {
                isSelected -> {
                    pill.setBackgroundColor(themeColor)
                    pill.typeface = Typeface.DEFAULT_BOLD
                }
                isToday -> {
                    pill.setBackgroundResource(R.drawable.schedule_pill_today_bg)
                    pill.typeface = Typeface.DEFAULT_BOLD
                }
                else -> {
                    pill.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    pill.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
                    pill.typeface = Typeface.DEFAULT
                }
            }
        }
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

// --- Card Adapter with DiffUtil for stable tab switching ---

class ScheduleCardAdapter(
    private val onItemClick: (WeeklyScheduleItem) -> Unit
) : ListAdapter<WeeklyScheduleItem, ScheduleCardAdapter.CardViewHolder>(ScheduleDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        val binding = ItemScheduleCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CardViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CardViewHolder(
        private val binding: ItemScheduleCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

        fun bind(item: WeeklyScheduleItem) {
            binding.schedulePosterBg.setImageDrawable(null)
            binding.scheduleLogo.setImageDrawable(null)

            val airTime = Instant.ofEpochMilli(item.airingAt)
                .atZone(ZoneId.systemDefault())
            binding.scheduleTime.text = airTime.format(timeFormatter)

            if (item.episodeNumber != null) {
                binding.scheduleEpisode.isVisible = true
                binding.scheduleEpisode.text = "Episode ${item.episodeNumber}"
            } else {
                binding.scheduleEpisode.isVisible = false
            }

            val logoUrl = item.scheduleLogoUrl
            if (!logoUrl.isNullOrBlank() && logoUrl.startsWith("http")) {
                binding.scheduleLogo.isVisible = true
                binding.scheduleName.isVisible = false
                // Tag guard: only apply the logo if this viewholder is still bound to this item
                binding.scheduleLogo.tag = logoUrl
                binding.scheduleLogo.loadImage(logoUrl) {
                    listener(
                        onSuccess = { _, _ ->
                            if (binding.scheduleLogo.tag != logoUrl) {
                                binding.scheduleLogo.setImageDrawable(null)
                            }
                        }
                    )
                }
            } else {
                binding.scheduleLogo.isVisible = false
                binding.scheduleName.isVisible = true
                binding.scheduleName.text = item.scheduleName
            }

            val imageUrl = item.scheduleBannerUrl ?: item.schedulePosterUrl
            if (!imageUrl.isNullOrBlank() && imageUrl.startsWith("http")) {
                // Tag guard: prevents a recycled viewholder from showing a stale banner
                binding.schedulePosterBg.tag = imageUrl
                binding.schedulePosterBg.loadImage(imageUrl) {
                    listener(
                        onSuccess = { _, _ ->
                            if (binding.schedulePosterBg.tag != imageUrl) {
                                binding.schedulePosterBg.setImageDrawable(null)
                            }
                        }
                    )
                }
            }

            binding.scheduleWatchBtn.setOnClickListener {
                onItemClick(item)
            }

            binding.scheduleCard.setOnClickListener {
                onItemClick(item)
            }
        }
    }

    private class ScheduleDiffCallback : DiffUtil.ItemCallback<WeeklyScheduleItem>() {
        override fun areItemsTheSame(oldItem: WeeklyScheduleItem, newItem: WeeklyScheduleItem): Boolean {
            return oldItem.scheduleId == newItem.scheduleId
        }

        override fun areContentsTheSame(oldItem: WeeklyScheduleItem, newItem: WeeklyScheduleItem): Boolean {
            return oldItem == newItem
        }
    }
}
