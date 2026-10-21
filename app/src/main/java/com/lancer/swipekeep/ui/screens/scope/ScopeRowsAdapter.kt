package com.lancer.swipekeep.ui.screens.scope

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lancer.swipekeep.R
import com.lancer.swipekeep.databinding.ItemScopeRowBinding
import com.lancer.swipekeep.domain.model.SortOrder
import com.lancer.swipekeep.ui.common.Formatters

/**
 * 范围行 + 月份行共用的适配器（单 view type，见 `item_scope_row.xml` 的注释）。
 *
 * **每次 bind 都要重置全部可变状态**（图标、背景、文字颜色、药丸、chevron、监听器）：
 * RecyclerView 会复用 View，只设置「当前行需要的东西」会让上一行的状态漏到下一行。
 */
class ScopeRowsAdapter(
    private val onStartAllPhotos: (SortOrder) -> Unit,
    private val onStartMonth: (ScopeRow.Month) -> Unit,
    private val onToggleSort: () -> Unit,
) : ListAdapter<ScopeRow, ScopeRowsAdapter.RowHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val binding = ItemScopeRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RowHolder(binding)
    }

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RowHolder(private val binding: ItemScopeRowBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: ScopeRow) {
            val context = binding.root.context
            when (row) {
                is ScopeRow.Action -> bindAction(context, row)
                is ScopeRow.Month -> bindMonth(context, row)
            }
        }

        private fun bindAction(context: Context, row: ScopeRow.Action) {
            val titleRes = when (row.kind) {
                ScopeRow.ActionKind.ALL_PHOTOS ->
                    if (row.sortOrder == SortOrder.NEWEST_FIRST) R.string.scope_all_photos else R.string.scope_all_photos_oldest

                ScopeRow.ActionKind.SCREENSHOTS -> R.string.scope_screenshots
                ScopeRow.ActionKind.SIMILAR -> R.string.scope_similar
                ScopeRow.ActionKind.VIDEOS -> R.string.scope_videos
            }
            binding.title.setText(titleRes)

            binding.subtitle.text = when (row.kind) {
                ScopeRow.ActionKind.ALL_PHOTOS -> context.getString(R.string.scope_count_photos, row.count ?: 0)
                ScopeRow.ActionKind.SCREENSHOTS -> context.getString(R.string.scope_soon_screenshots)
                ScopeRow.ActionKind.SIMILAR -> context.getString(R.string.scope_soon_similar)
                ScopeRow.ActionKind.VIDEOS -> context.getString(R.string.scope_soon_videos)
            }

            val iconRes = when (row.kind) {
                ScopeRow.ActionKind.ALL_PHOTOS -> R.drawable.ic_scope_all
                ScopeRow.ActionKind.SCREENSHOTS -> R.drawable.ic_scope_screenshots
                ScopeRow.ActionKind.SIMILAR -> R.drawable.ic_scope_similar
                ScopeRow.ActionKind.VIDEOS -> R.drawable.ic_scope_videos
            }
            val tileColorRes = when (row.kind) {
                ScopeRow.ActionKind.ALL_PHOTOS -> R.color.tile_blue
                ScopeRow.ActionKind.SCREENSHOTS -> R.color.tile_purple
                ScopeRow.ActionKind.SIMILAR -> R.color.tile_green
                ScopeRow.ActionKind.VIDEOS -> R.color.tile_orange
            }
            bindTile(iconRes, tileColorRes)

            applyEnabledState(context, enabled = row.enabled)

            when (row.kind) {
                ScopeRow.ActionKind.ALL_PHOTOS -> {
                    // 排序切换挂在「推荐」药丸上，与「开始清理」的点击目标分开。
                    binding.pill.isVisible = true
                    binding.pill.setBackgroundResource(R.drawable.bg_pill_recommend)
                    binding.pill.setTextColor(color(context, R.color.pill_recommend_text))
                    binding.pill.setText(R.string.pill_recommend)
                    binding.pill.contentDescription = context.getString(
                        R.string.cd_scope_sort_toggle,
                        Formatters.sortLabel(context, row.sortOrder),
                    )
                    binding.pill.isClickable = true
                    binding.pill.setOnClickListener { onToggleSort() }

                    binding.rowRoot.setOnClickListener { onStartAllPhotos(row.sortOrder) }
                }

                else -> {
                    binding.pill.isVisible = true
                    binding.pill.setBackgroundResource(R.drawable.bg_pill_soon)
                    binding.pill.setTextColor(color(context, R.color.pill_soon_text))
                    binding.pill.setText(R.string.pill_coming_soon)
                    binding.pill.contentDescription = null
                    binding.pill.isClickable = false
                    binding.pill.setOnClickListener(null)

                    // 不可点：连 ripple 都不该有，否则点下去像是「按了但没反应」。
                    binding.rowRoot.setOnClickListener(null)
                }
            }
        }

        private fun bindMonth(context: Context, row: ScopeRow.Month) {
            val bucket = row.bucket
            binding.title.text = Formatters.monthLabel(context, bucket.year, bucket.month, bucket.isUnknown)
            binding.subtitle.text = context.getString(R.string.scope_month_count, bucket.count)
            bindTile(R.drawable.ic_scope_month, R.color.tile_pink)

            // 月份行一律可点、一律没有药丸 —— 与 Action 行共用布局，所以必须显式复位。
            applyEnabledState(context, enabled = true)
            binding.pill.isVisible = false
            binding.pill.contentDescription = null
            binding.pill.isClickable = false
            binding.pill.setOnClickListener(null)
            binding.rowRoot.setOnClickListener { onStartMonth(row) }
        }

        private fun bindTile(iconRes: Int, tileColorRes: Int) {
            val context = binding.root.context
            binding.tile.setImageResource(iconRes)
            binding.tile.imageTintList = ColorStateList.valueOf(color(context, R.color.white))
            binding.tile.backgroundTintList = ColorStateList.valueOf(color(context, tileColorRes))
        }

        private fun applyEnabledState(context: Context, enabled: Boolean) {
            binding.rowRoot.isEnabled = enabled
            binding.rowRoot.isClickable = enabled
            binding.rowRoot.isFocusable = enabled
            binding.rowRoot.alpha = 1f
            binding.rowRoot.setCardBackgroundColor(color(context, R.color.white))
            binding.title.setTextColor(color(context, R.color.ink))
            binding.subtitle.setTextColor(color(context, R.color.text_secondary))
            binding.chevron.imageTintList = ColorStateList.valueOf(
                color(context, if (enabled) R.color.chevron else R.color.chevron_disabled),
            )
        }

        private fun color(context: Context, res: Int): Int = ContextCompat.getColor(context, res)
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ScopeRow>() {
            override fun areItemsTheSame(oldItem: ScopeRow, newItem: ScopeRow): Boolean = oldItem.key == newItem.key

            // data class 的 equals 覆盖了「排序变了」「计数变了」这些需要重绘的情况。
            override fun areContentsTheSame(oldItem: ScopeRow, newItem: ScopeRow): Boolean = oldItem == newItem
        }
    }
}
