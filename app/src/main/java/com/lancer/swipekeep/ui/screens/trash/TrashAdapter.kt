package com.lancer.swipekeep.ui.screens.trash

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.load
import com.lancer.swipekeep.R
import com.lancer.swipekeep.data.media.ThumbnailFetcher
import com.lancer.swipekeep.databinding.ItemTrashBinding
import com.lancer.swipekeep.domain.model.PhotoItem
import com.lancer.swipekeep.ui.common.Formatters

/**
 * 回收站列表。每一项带「恢复」与「彻底删除」两个动作。
 *
 * 缩略图**走 `loadThumbnail`**（带 [ThumbnailFetcher.PARAM_USE_PROVIDER_THUMBNAIL]）：
 * 这里是 56dp 的小图，`loadThumbnail` 只给源图的 1/4 也远远够用，而它比解码原图快 5 倍
 * （见 ThumbnailFetcher 的实测表）。回收站列表可能有上千行，这一点不是微优化。
 */
class TrashAdapter(
    private val imageLoader: ImageLoader,
    private val uriOf: (PhotoItem) -> Uri,
    private val onRestore: (PhotoItem) -> Unit,
    private val onDeleteForever: (PhotoItem) -> Unit,
) : ListAdapter<PhotoItem, TrashAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemTrashBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemTrashBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PhotoItem) {
            val context = binding.root.context
            binding.name.text = item.displayName
            binding.meta.text = context.getString(
                R.string.trash_item_meta,
                Formatters.fileSize(context, item.sizeBytes),
                Formatters.date(context, item.takenAt),
            )
            binding.thumb.load(uriOf(item), imageLoader) {
                size(THUMB_PX, THUMB_PX)
                setParameter(ThumbnailFetcher.PARAM_USE_PROVIDER_THUMBNAIL, true)
            }

            // 每次 bind 都重设监听器：ViewHolder 会复用，监听器捕获的是**上一个** item。
            binding.restore.setOnClickListener { onRestore(item) }
            binding.deleteForever.setOnClickListener { onDeleteForever(item) }
        }
    }

    private companion object {
        /** 56dp 在 3x 屏上是 ~168px。给到 192 留一点放大余量。 */
        const val THUMB_PX = 192

        val DIFF = object : DiffUtil.ItemCallback<PhotoItem>() {
            override fun areItemsTheSame(oldItem: PhotoItem, newItem: PhotoItem): Boolean =
                oldItem.identity == newItem.identity

            override fun areContentsTheSame(oldItem: PhotoItem, newItem: PhotoItem): Boolean =
                oldItem == newItem
        }
    }
}
