package com.lagradost.cloudstream3.ui.player.source_priority

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.PlayerPrioritizeItemBinding
import com.lagradost.cloudstream3.ui.NoStateAdapter
import com.lagradost.cloudstream3.ui.ViewHolderState

data class SourcePriority<T>(
    val data: T,
    val name: String,
    var priority: Int,
    var isFavorite: Boolean = false
)

class PriorityAdapter<T>(
    private val showFavorite: Boolean = false,
    private val onFavoriteChanged: ((name: String, isFavorite: Boolean) -> Unit)? = null
) : NoStateAdapter<SourcePriority<T>>() {

    override fun onCreateContent(parent: ViewGroup): ViewHolderState<Any> {
        return ViewHolderState(
            PlayerPrioritizeItemBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindContent(
        holder: ViewHolderState<Any>,
        item: SourcePriority<T>,
        position: Int
    ) {
        val binding = holder.view as? PlayerPrioritizeItemBinding ?: return
        binding.priorityText.text = item.name

        fun updatePriority() {
            binding.priorityNumber.text = item.priority.toString()
        }

        updatePriority()
        binding.addButton.setOnClickListener {
            // If someone clicks til the integer limit then they deserve to crash.
            item.priority++
            updatePriority()
        }

        binding.subtractButton.setOnClickListener {
            item.priority--
            updatePriority()
        }

        if (showFavorite) {
            binding.favoriteButton.isVisible = true
            binding.favoriteButton.setImageResource(
                if (item.isFavorite) R.drawable.ic_baseline_star_24
                else R.drawable.ic_baseline_star_border_24
            )
            binding.favoriteButton.setOnClickListener {
                item.isFavorite = !item.isFavorite
                binding.favoriteButton.setImageResource(
                    if (item.isFavorite) R.drawable.ic_baseline_star_24
                    else R.drawable.ic_baseline_star_border_24
                )
                onFavoriteChanged?.invoke(item.name, item.isFavorite)
            }
        } else {
            binding.favoriteButton.isVisible = false
        }
    }
}
