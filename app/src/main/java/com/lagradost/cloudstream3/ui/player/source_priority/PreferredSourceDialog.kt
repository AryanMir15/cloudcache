package com.lagradost.cloudstream3.ui.player.source_priority

import android.app.Dialog
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.annotation.StyleRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.databinding.PreferredSourceAvailableItemBinding
import com.lagradost.cloudstream3.databinding.PreferredSourceDialogBinding
import com.lagradost.cloudstream3.databinding.PreferredSourceSelectedItemBinding
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding

class PreferredSourceDialog(
    private val ctx: android.content.Context,
    @StyleRes themeRes: Int
) : Dialog(ctx, themeRes) {

    private data class SourceItem(val name: String)

    private class SelectedAdapter(
        private val onRemove: (String) -> Unit,
        private val onDragStart: ((RecyclerView.ViewHolder) -> Unit)? = null
    ) : RecyclerView.Adapter<SelectedAdapter.VH>() {

        private val items = mutableListOf<SourceItem>()

        fun submitList(newItems: List<SourceItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        fun getItems(): List<SourceItem> = items.toList()

        fun moveItem(from: Int, to: Int) {
            val item = items.removeAt(from)
            items.add(to, item)
            notifyItemMoved(from, to)
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = PreferredSourceSelectedItemBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.binding.sourceName.text = item.name
            holder.binding.removeButton.setOnClickListener {
                onRemove(item.name)
            }
            holder.binding.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onDragStart?.invoke(holder)
                }
                false
            }
        }

        inner class VH(val binding: PreferredSourceSelectedItemBinding) :
            RecyclerView.ViewHolder(binding.root)
    }

    private class AvailableAdapter(
        private val onSelect: (String) -> Unit
    ) : RecyclerView.Adapter<AvailableAdapter.VH>() {

        private val items = mutableListOf<SourceItem>()

        fun submitList(newItems: List<SourceItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        fun getItems(): List<SourceItem> = items.toList()

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = PreferredSourceAvailableItemBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.binding.sourceName.text = item.name
            holder.itemView.setOnClickListener {
                onSelect(item.name)
            }
        }

        inner class VH(val binding: PreferredSourceAvailableItemBinding) :
            RecyclerView.ViewHolder(binding.root)
    }

    private var itemTouchHelper: ItemTouchHelper? = null

    override fun show() {
        val binding = PreferredSourceDialogBinding.inflate(
            LayoutInflater.from(ctx), null, false
        )
        setContentView(binding.root)
        fixSystemBarsPadding(binding.root)

        val favorites = QualityDataHelper.getFavoriteSources()
        val selectedNames = QualityDataHelper.getSelectedSources().filter { favorites.contains(it) }
        val availableNames = favorites.filter { !selectedNames.contains(it) }

        val selectedItems = selectedNames.map { SourceItem(it) }.toMutableList()
        val availableItems = availableNames.map { SourceItem(it) }.toMutableList()

        fun refreshUI() {
            binding.selectedSourcesList.isVisible = selectedItems.isNotEmpty()
            binding.availableSourcesList.isVisible = availableItems.isNotEmpty()
            binding.emptyStateText.isVisible = favorites.isEmpty()
            (binding.selectedSourcesList.adapter as? SelectedAdapter)?.submitList(selectedItems.toList())
            (binding.availableSourcesList.adapter as? AvailableAdapter)?.submitList(availableItems.toList())
        }

        val selectedAdapter = SelectedAdapter(
            onRemove = { name ->
                val idx = selectedItems.indexOfFirst { it.name == name }
                if (idx >= 0) {
                    selectedItems.removeAt(idx)
                    availableItems.add(SourceItem(name))
                    refreshUI()
                }
            },
            onDragStart = { holder ->
                itemTouchHelper?.startDrag(holder)
            }
        )

        val availableAdapter = AvailableAdapter { name ->
            val idx = availableItems.indexOfFirst { it.name == name }
            if (idx >= 0) {
                availableItems.removeAt(idx)
                selectedItems.add(SourceItem(name))
                refreshUI()
            }
        }

        binding.selectedSourcesList.adapter = selectedAdapter
        binding.availableSourcesList.adapter = availableAdapter

        // Drag to reorder selected sources
        val touchCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.absoluteAdapterPosition
                val to = target.absoluteAdapterPosition
                selectedAdapter.moveItem(from, to)
                // Also update our local list
                val item = selectedItems.removeAt(from)
                selectedItems.add(to, item)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled() = false
        }
        itemTouchHelper = ItemTouchHelper(touchCallback)
        itemTouchHelper?.attachToRecyclerView(binding.selectedSourcesList)

        refreshUI()

        binding.selectAllBtt.setOnClickListener {
            selectedItems.addAll(availableItems)
            availableItems.clear()
            refreshUI()
        }

        binding.clearAllBtt.setOnClickListener {
            availableItems.addAll(selectedItems)
            selectedItems.clear()
            refreshUI()
        }

        binding.doneBtt.setOnClickListener {
            QualityDataHelper.setSelectedSources(selectedItems.map { it.name })
            dismissSafe()
        }

        super.show()
    }
}
