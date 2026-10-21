package com.lancer.swipekeep.ui.widget

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.load
import coil.request.ImageRequest
import com.google.android.material.card.MaterialCardView
import com.lancer.swipekeep.R
import com.lancer.swipekeep.data.media.ThumbnailFetcher
import com.lancer.swipekeep.databinding.ItemPhotoCardBinding
import com.lancer.swipekeep.domain.model.PhotoItem
import com.lancer.swipekeep.ui.common.Formatters
import kotlin.math.abs

/**
 * 一张照片卡片：照片 + 底部信息条 + 22% 遮罩 + 印章。
 *
 * 由 [SwipeCardStackView] 建几个实例反复复用，**本类不持有任何手势状态** ——
 * 拖拽位移与旋转都由卡片堆统一驱动，这里只负责「按给定的进度把印章画出来」。
 *
 * 为什么继承 `MaterialCardView` 而不是自绘圆角背景：阶段 1 的对照页用的就是它，
 * 白底 / 24dp 圆角 / 1dp 描边 / 8dp 阴影这套观感已经与设计图逐项比对过，
 * 换一套自绘实现等于把那轮验证作废。
 */
class PhotoCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : MaterialCardView(context, attrs, defStyleAttr) {

    private val binding = ItemPhotoCardBinding.inflate(LayoutInflater.from(context), this, true)

    /** 当前印章画的是哪一种。`null` = 还没画过，所以第一次非零进度必然要走一遍换资源的逻辑。 */
    private var showingDelete: Boolean? = null

    init {
        setCardBackgroundColor(color(R.color.white))
        radius = resources.getDimension(R.dimen.card_corner)
        cardElevation = resources.getDimension(R.dimen.card_elevation)
    }

    /**
     * 绑定一张照片。**顶卡与后排走两条完全不同的解码路径**（见 [ThumbnailFetcher] 的实测表格）：
     *
     * - 顶卡：请求 720×960 且**不带**缩略图参数 → Coil 解码原图并降采样，得到与卡片同宽（~720px）
     *   的清晰位图。实测 ~180ms、~4.5MB，但它是被 [SwipeCardStackView.preloadAhead] 提前一两个
     *   身位预热的，轮到它当顶卡时命中的是内存缓存，看不到这 180ms。
     * - 后排：请求 360×480 且带上缩略图参数 → 走 `loadThumbnail`，实测 ~6ms、~0.6MB。
     *   它只露一条边，1/4 尺寸的糊在这看不到。
     *
     * 两条路径的解码尺寸不同，所以在 Coil 的内存缓存里是**两笔独立条目** ——
     * 这正是「后排卡升为顶卡时按顶卡尺寸重绑一次」能立刻命中的原因。
     */
    fun bind(item: PhotoItem, uri: Uri, imageLoader: ImageLoader, isFront: Boolean) {
        val width = if (isFront) FRONT_WIDTH else BACK_WIDTH
        val height = if (isFront) FRONT_HEIGHT else BACK_HEIGHT
        binding.photoImage.load(uri, imageLoader) {
            size(width, height)
            if (!isFront) {
                setParameter(ThumbnailFetcher.PARAM_USE_PROVIDER_THUMBNAIL, true)
            }
        }
        binding.caption.text = context.getString(
            R.string.photo_meta_format,
            item.displayName,
            Formatters.fileSize(context, item.sizeBytes),
            Formatters.date(context, item.takenAt),
        )
        resetStamp()
    }

    /**
     * 按滑动进度画印章与遮罩。[progress] 带符号：**负数 = 删除（红）**，正数 = 保留（绿）。
     *
     * alpha 用 `|p| * (0.35 + 0.65*|p|)`，而不是计划书里的 `0.35 + 0.65*|p|`：
     * 后者在 `p = 0`（手指刚碰到、还没滑）时会留下一个 0.35 alpha 的印章，
     * 而设计图 01 的静止态是干净的、什么都看不到的。乘一个 `|p|` 就同时满足
     * 「静止为 0」与「滑到底为 1」，且中间段单调。
     *
     * 换资源只在方向真的翻转时做一次：`setBackgroundResource` 会重新解析 drawable，
     * 放在每帧都走的路径上等于每帧 allocate 一个新 drawable。
     */
    fun setStamp(progress: Float) {
        val magnitude = abs(progress).coerceIn(0f, 1f)
        val alpha = magnitude * (STAMP_ALPHA_MIN + (1f - STAMP_ALPHA_MIN) * magnitude)
        binding.stamp.alpha = alpha
        binding.scrim.alpha = alpha

        if (magnitude <= 0f) return
        val deleting = progress < 0f
        if (deleting == showingDelete) return
        showingDelete = deleting

        binding.stamp.setBackgroundResource(
            if (deleting) R.drawable.bg_stamp_delete else R.drawable.bg_stamp_keep,
        )
        binding.stamp.setTextColor(color(if (deleting) R.color.delete_red else R.color.keep_green))
        binding.stamp.setText(if (deleting) R.string.stamp_delete else R.string.stamp_keep)
        // 印章自身也镜像：删除左倾、保留右倾（设计图 02 / 03）
        binding.stamp.rotation = if (deleting) -STAMP_ROTATION else STAMP_ROTATION
        binding.scrim.setBackgroundColor(color(if (deleting) R.color.scrim_delete else R.color.scrim_keep))
    }

    /** 复位到静止态。卡片飞出后归零位移时必须一起清掉，否则它带着满 alpha 的印章出现在后排。 */
    fun resetStamp() = setStamp(0f)

    private fun color(res: Int): Int = ContextCompat.getColor(context, res)

    companion object {
        /**
         * 顶卡的解码尺寸。约等于 276dp 内图在 440dpi 屏上的像素数。
         * 后排卡只露一条边，解码尺寸减半。
         */
        private const val FRONT_WIDTH = 720
        private const val FRONT_HEIGHT = 960
        private const val BACK_WIDTH = 360
        private const val BACK_HEIGHT = 480

        private const val STAMP_ALPHA_MIN = 0.35f

        /** 印章自身的倾斜角度（设计图 02 左倾 -6、03 右倾 +6） */
        private const val STAMP_ROTATION = 6f

        /**
         * 只把位图解码进缓存，不绑到任何 ImageView 上。给 [SwipeCardStackView] 的预热用。
         *
         * 预热刻意用**顶卡尺寸**而不是后排尺寸：一张卡从后排走到顶卡只隔一两次滑动，
         * 而顶卡是要被细看的那张。省下的那点内存换不来「升到顶卡时糊一下」的代价。
         */
        fun preload(context: Context, uri: Uri, imageLoader: ImageLoader) {
            imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(uri)
                    .size(FRONT_WIDTH, FRONT_HEIGHT)
                    .build(),
            )
        }
    }
}
