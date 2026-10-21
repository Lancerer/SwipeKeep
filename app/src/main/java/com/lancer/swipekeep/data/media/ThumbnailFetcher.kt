package com.lancer.swipekeep.data.media

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.Size
import coil.size.pxOrElse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 让 Coil 走 `ContentResolver.loadThumbnail` 而不是「解码原图再降采样」。
 *
 * ## 实测数据（小米 10 / API 33，开放项 #6 与 #7，阶段 3 测得）
 *
 * | 路径 | 输出尺寸 | 端到端耗时 | 内存 |
 * |---|---|---|---|
 * | 本 Fetcher（`loadThumbnail`） | 源图的 1/4（1080×2340 的截图 → 270×585） | 30–36ms | ~0.6MB |
 * | Coil 默认（解码原图再降采样） | 忠于请求（720×960 → 720×1560） | 160–190ms | ~4.5MB |
 *
 * 两个结论：
 * 1. **开放项 #7 已答**：探针日志能打出来，说明本 Fetcher 确实排在 Coil 内置的
 *    `ContentUriFetcher` **之前**被调用，计划书担心的「排在后面所以永远不被调用」不成立。
 * 2. **真正的问题不是快慢，而是清晰度**：`loadThumbnail` **无视请求尺寸**，
 *    一律返回源图的 1/4。对 4000×3000 的相机原图（→ 1000×750）够用，
 *    但对 1080 宽的截图/微信图（→ 270 宽）贴到 759px 宽的卡片上就是 2.8 倍放大、肉眼可见地虚。
 *
 * 因此本 Fetcher **只服务后排卡片**（顶卡要清晰，走 Coil 默认），见 [Factory]。
 * 代价是保留两层，收益是「看得最仔细的那张永远清晰，被盖住的三张几乎不花钱」。
 */
class ThumbnailFetcher(
    private val context: Context,
    private val uri: Uri,
    private val requestedSize: Size,
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        // Coil 2.7 的 Size 持的是 Dimension（可能是 Pixels，也可能是 Undefined/ORIGINAL）。
        // loadThumbnail 遇到非正数尺寸会抛 IllegalArgumentException，所以未定尺寸必须换成
        // 一个具体值 —— pxOrElse 就是 Coil 给这件事准备的入口。
        val width = requestedSize.width.pxOrElse { DEFAULT_LONG_EDGE }.coerceAtLeast(1)
        val height = requestedSize.height.pxOrElse { DEFAULT_LONG_EDGE }.coerceAtLeast(1)

        val startedAt = SystemClock.elapsedRealtime()
        val loaded = runCatching {
            context.contentResolver.loadThumbnail(uri, android.util.Size(width, height), null)
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val bitmap = loaded.getOrNull()

        // 打在**后排**卡片的路径上。想复查清晰度/耗时对比时，临时去掉 PhotoCardView 里的
        // 参数就会退回 Coil 默认路径，两边的数字在类注释的表格里。
        Log.d(TAG, "loadThumbnail ${width}x$height -> ${bitmap?.let { "${it.width}x${it.height}" } ?: "fail"} in ${elapsed}ms")

        if (bitmap == null) {
            Log.w(TAG, "loadThumbnail failed", loaded.exceptionOrNull())
            return@withContext null
        }

        DrawableResult(
            drawable = BitmapDrawable(context.resources, bitmap),
            // 是采样过的位图（缩略图本身就是采样产物）。这个标记影响 Coil 的内存缓存策略。
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {

        /**
         * 返回 null = 「这个 URI 我不管」，Coil 会继续找下一个 Fetcher。
         *
         * 两道闸门：
         * 1. 只接 MediaStore 的 URI，别的一律放行给默认实现。
         * 2. **只接调用方明确要求「缩略图够用」的请求**（[PARAM_USE_PROVIDER_THUMBNAIL]）。
         *    原因见类注释里那段实测数据：`loadThumbnail` 只给源图的 1/4 尺寸，
         *    后排卡片被盖住大半、看不出来，顶卡却会明显发虚。
         */
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (!MediaStoreDataSource.isMediaUri(data)) return null
            val wantsThumbnail =
                options.parameters.value<Boolean>(PARAM_USE_PROVIDER_THUMBNAIL) == true
            return if (wantsThumbnail) ThumbnailFetcher(context, data, options.size) else null
        }
    }

    companion object {
        private const val TAG = "ThumbnailFetcher"

        /** 只会在 Size.ORIGINAL 那条路径上用到 */
        private const val DEFAULT_LONG_EDGE = 1024

        /**
         * 请求参数：标记「这张走系统缩略图就够」。
         *
         * 只有**后排卡片**会带上它（见 `PhotoCardView.bind`）。顶卡刻意不带，
         * 让 Coil 走默认的解码原图路径，换来与卡片同宽的清晰度。
         */
        const val PARAM_USE_PROVIDER_THUMBNAIL = "swipekeep.providerThumbnail"
    }
}

/** 生产环境的 ImageLoader。放在这里是为了让 `AppContainer` 不依赖 Coil 的类型细节。 */
fun buildImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
    // 它只接「带 PARAM_USE_PROVIDER_THUMBNAIL 参数」的请求（后排卡片），其余照旧走 Coil 默认。
    .components { add(ThumbnailFetcher.Factory(context)) }
    // 关掉淡入：滑卡时刚浮出的卡片做淡入会被眼睛读成「闪了一下」，而不是「换了张图」。
    .crossfade(false)
    .build()
