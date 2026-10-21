package com.lancer.swipekeep.ui.screens.scope

import android.content.Context
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
 * **每次 bind 都要重置全部可变状态**（alpha / isEnabled / 监听器 / 药丸的可见性与配色）：
 * RecyclerView 会复用 View，只设置「当前行需要的东西」会让上一行的置灰状态漏到下一行 ——
 * 这类 bug 只在列表滚动几屏之后才出现，很容易漏测。
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

            binding.tile.setBackgroundResource(
                when (row.kind) {
                    ScopeRow.ActionKind.ALL_PHOTOS -> R.drawable.bg_scope_tile_1
                    ScopeRow.ActionKind.SCREENSHOTS -> R.drawable.bg_scope_tile_2
                    ScopeRow.ActionKind.SIMILAR -> R.drawable.bg_scope_tile_3
                    ScopeRow.ActionKind.VIDEOS -> R.drawable.bg_scope_tile_4
                },
            )

            applyEnabledState(enabled = row.enabled)

            when (row.kind) {
                ScopeRow.ActionKind.ALL_PHOTOS -> {
                    // 排序切换挂在「推荐」药丸上：设计图里没有排序控件，而这一行的标题本身就写着
                    // 「从最近开始 / 从最旧开始」，点标题是「开始清理」。两件事不能共用一个点击目标，
                    // 所以把切换放到紧挨着的小药丸上 —— 不新增视觉元素，位置也贴着它影响的那行字。
                    binding.pill.isVisible = true
                    binding.pill.setBackgroundResource(R.drawable.bg_pill_recommend)
                    binding.pill.setTextColor(ContextCompat.getColor(context, R.color.brand_orange))
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
                    binding.pill.setBackgroundResource(R.drawable.bg_pill_muted)
                    binding.pill.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    binding.pill.setText(R.string.pill_coming_soon)
                    binding.pill.isClickable = false
                    binding.pill.setOnClickListener(null)

                    // 不可点：连 ripple 都不该有，否则点下去像是「按了但没反应」而不是「这功能没有」。
                    binding.rowRoot.setOnClickListener(null)
                }
            }
        }

        private fun bindMonth(context: Context, row: ScopeRow.Month) {
            val bucket = row.bucket
            binding.title.text = Formatters.monthLabel(context, bucket.year, bucket.month, bucket.isUnknown)
            binding.subtitle.text = context.getString(R.string.scope_month_count, bucket.count)
            binding.tile.setBackgroundResource(R.drawable.bg_scope_tile_5)

            // 月份行一律可点、一律没有药丸 —— 与 Action 行共用布局，所以必须显式复位。
            applyEnabledState(enabled = true)
            binding.pill.isVisible = false
            binding.pill.setOnClickListener(null)
            binding.rowRoot.setOnClickListener { onStartMonth(row) }
        }

        private fun applyEnabledState(enabled: Boolean) {
            binding.rowRoot.isEnabled = enabled
            binding.rowRoot.isClickable = enabled
            // 0.45 与设计图里置灰行的观感一致（阶段 1 对照页已按这个值比对过）。
            binding.rowRoot.alpha = if (enabled) 1f else DISABLED_ALPHA
        }
    }

    private companion object {
        const val DISABLED_ALPHA = 0.45f

        val DIFF = object : DiffUtil.ItemCallback<ScopeRow>() {
            override fun areItemsTheSame(oldItem: ScopeRow, newItem: ScopeRow): Boolean = oldItem.key == newItem.key

            // data class 的 equals 覆盖了「排序变了」「计数变了」这些需要重绘的情况。
            override fun areContentsTheSame(oldItem: ScopeRow, newItem: ScopeRow): Boolean = oldItem == newItem
        }
    }
}
