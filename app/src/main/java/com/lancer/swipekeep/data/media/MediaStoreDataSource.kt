package com.lancer.swipekeep.data.media

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.lancer.swipekeep.domain.model.PhotoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **全 App 唯一允许 import `android.provider.MediaStore` 的文件**（实施计划 §3）。
 * 其它任何地方需要照片，都从 `PhotoRepository` 拿 [PhotoItem]，不直接碰 ContentResolver。
 * 这条约束的价值是：MediaStore 的坑（volume、时间单位、回收站过滤）全部收敛在这一个文件里。
 */
class MediaStoreDataSource(private val context: Context) {

    /**
     * 全量扫一遍照片元数据。**只取元数据，不解码位图** —— 1 万行 × 几个字段约 1MB，
     * 位图交给 Coil 的有界 LRU。这也是不做 Paging 的原因（实施计划 §4.2）。
     */
    suspend fun loadPhotos(): List<PhotoItem> = withContext(Dispatchers.IO) {
        val volumes = externalVolumeNames()
        val out = ArrayList<PhotoItem>()
        for (volume in volumes) {
            // 没权限时 MediaProvider 是**直接抛 SecurityException**，不是返回空集。
            // 这里按「这个 volume 没照片」处理，而不是让它冒到上层崩掉 ——
            // 「没权限」与「相册是空的」的区别由权限 UI 负责呈现，不该靠一个异常来传递。
            //
            // 但**必须记日志**：把异常完全吞掉会让「权限正常、查询写错了」表现成
            // 「相册是空的」，那是最难查的一类 bug（第一版就踩了：范围页空列表，
            // 而 dumpsys 显示权限是 granted）。
            out += runCatching { queryVolume(volume, MediaStore.MATCH_EXCLUDE, skipTrashedRows = true) }
                .onFailure { Log.w(TAG, "query volume=$volume failed", it) }
                .getOrElse { emptyList() }
        }
        // 「扫描了多少张」是排查「计数对不上 / 列表没更新」的第一个要看的数字，
        // 一行 debug 日志换掉一整轮「加日志再装一遍」的成本。
        Log.d(TAG, "loadPhotos: ${out.size} photos across ${volumes.size} volume(s)")
        out
    }

    /**
     * 系统回收站里的照片。
     *
     * 用 `MATCH_ONLY` —— `QUERY_ARG_MATCH_TRASHED` 的取值里**没有 `MATCH_TRASHED`**，
     * `MATCH_ONLY` 才是「只要回收站里的」（阶段 0 用 `javap` 核实过）。
     *
     * 这里**不能**像 [loadPhotos] 那样在 Kotlin 侧再过滤一遍 `IS_TRASHED == 1` 的行 ——
     * 那正好会把我们要的每一行都丢掉。所以 [queryVolume] 的兜底过滤做成开关。
     *
     * 不缓存：系统可能已经让某些项过了 7 天自动消失，而回收站页每次进来都该看到真值
     * （实施计划 §4.4）。
     */
    suspend fun loadTrashedPhotos(): List<PhotoItem> = withContext(Dispatchers.IO) {
        val volumes = externalVolumeNames()
        val out = ArrayList<PhotoItem>()
        for (volume in volumes) {
            out += runCatching { queryVolume(volume, MediaStore.MATCH_ONLY, skipTrashedRows = false) }
                .onFailure { Log.w(TAG, "query trashed volume=$volume failed", it) }
                .getOrElse { emptyList() }
        }
        Log.d(TAG, "loadTrashedPhotos: ${out.size} photos across ${volumes.size} volume(s)")
        out
    }

    /**
     * 需要监听变化的 collection URI，每个 volume 一个。
     * 写成公开方法是为了让 Repository 注册 ContentObserver 时不必自己拼 URI、不必 import MediaStore。
     */
    fun observableCollections(): List<Uri> = externalVolumeNames().map { MediaStore.Images.Media.getContentUri(it) }

    /**
     * 按 item 的所属 volume 拼出可写回的 URI。
     *
     * **必须逐个 volume 拼**：`_ID` 只在单个 volume 内唯一，拿 `EXTERNAL_CONTENT_URI` 拼出来的
     * URI 对 SD 卡上的照片是错的 —— 删除会失败或删错。这是实施计划 §4.2 点名的坑。
     */
    fun uriFor(item: PhotoItem): Uri = uriFor(item.identity)

    /** 只拿身份也能拼 URI —— 结算页要提交的是「身份列表」，它不持整条 [PhotoItem]。 */
    fun uriFor(identity: PhotoItem.Identity): Uri =
        MediaStore.Images.Media.getContentUri(identity.volumeName, identity.id)

    /**
     * 移入 / 移出系统回收站。返回的 `PendingIntent` 必须由 **Activity** 启动
     * （`StartIntentSenderForResult`），ViewModel 自己 launch 不了。
     *
     * [trashed] 为 false 是「恢复」。开放项 #1（恢复方向是否也弹确认框）尚未实测，
     * 但调用方式完全一样，所以两种结果都不用改代码。
     *
     * URI 数量超过 [TrashCommitter.MAX_URIS_PER_REQUEST] 时 Android 抛
     * `IllegalArgumentException` —— 分块由 [TrashCommitter] 负责，调用方还要接住这个异常
     * 作为「块还是太大」的信号。
     */
    fun createTrashRequest(uris: Collection<Uri>, trashed: Boolean): PendingIntent =
        MediaStore.createTrashRequest(context.contentResolver, uris, trashed)

    /**
     * **彻底删除** —— 唯一真正释放空间的调用。
     *
     * `createTrashRequest` 只是把 `IS_TRASHED` 置 1，字节要等系统清理回收站才回来
     * （实施计划 §1 第 1 条）。所以「已释放」这个词只能出现在这一步之后。
     */
    fun createDeleteRequest(uris: Collection<Uri>): PendingIntent =
        MediaStore.createDeleteRequest(context.contentResolver, uris)

    private fun externalVolumeNames(): List<String> =
        MediaStore.getExternalVolumeNames(context).toList().ifEmpty {
            // 理论上不会发生（至少有 external_primary）；真发生也要能退回一个可用值，
            // 否则范围页会显示成「空相册」让人误判成没权限。
            listOf(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

    /**
     * @param matchTrashed `MATCH_EXCLUDE`（正常相册）或 `MATCH_ONLY`（回收站）
     * @param skipTrashedRows 是否在 Kotlin 侧丢掉 `IS_TRASHED == 1` 的行。
     *   正常相册查询用它做兜底；回收站查询必须是 false，否则一行都不剩。
     */
    private fun queryVolume(volume: String, matchTrashed: Int, skipTrashedRows: Boolean): List<PhotoItem> {
        val collection = MediaStore.Images.Media.getContentUri(volume)
        val bundle = Bundle().apply {
            // 让 provider 少传一些行（能用就用）。**正确性不靠它** —— 见 mapRows 里的 Kotlin 侧过滤。
            // 不在 bundle 里排序：排序一律在 Kotlin 侧做，见 PhotoBuckets。
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, matchTrashed)
        }

        return context.contentResolver
            .query(collection, PROJECTION, bundle, null)
            ?.use { cursor -> cursor.mapRows(volume, skipTrashedRows) }
            .orEmpty()
    }

    private fun Cursor.mapRows(volume: String, skipTrashedRows: Boolean): List<PhotoItem> {
        val idIdx = getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val nameIdx = getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val sizeIdx = getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val takenIdx = getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
        val addedIdx = getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
        val modifiedIdx = getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
        val mimeIdx = getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
        val widthIdx = getColumnIndex(MediaStore.MediaColumns.WIDTH)
        val heightIdx = getColumnIndex(MediaStore.MediaColumns.HEIGHT)
        val trashedIdx = getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)

        var skippedTrashed = 0
        val result = ArrayList<PhotoItem>(count)
        while (moveToNext()) {
            // **回收站过滤的兜底。** bundle 参数（`QUERY_ARG_MATCH_TRASHED = MATCH_EXCLUDE`）
            // 在小米 10 / API 33 上实测是生效的（cursor 返回 1978 行、其中 0 行进回收站，
            // 与 `content query` 的计数完全一致）。但各 OEM 的 MediaProvider 对 bundle 参数的
            // 兑现程度不一，而「已进回收站的照片重新出现在滑卡堆里」会让用户去删一张已经删掉的
            // 照片、结算数字也跟着错 —— 用一次 int 比较把这条不变量从 provider 行为里摘出来，
            // 是整个查询路径上性价比最高的一处冗余。
            if (skipTrashedRows && trashedIdx >= 0 && getInt(trashedIdx) == 1) {
                skippedTrashed++
                continue
            }
            // volumeName 取自**外层循环变量**而不是 VOLUME_NAME 列 —— 我们本来就是逐 volume 查的，
            // 所以它必然正确；这样即使某版本上 VOLUME_NAME 没被填充（开放项 #3）也不受影响。
            // 代价是放弃用这一列做交叉校验：真要保持警惕的是「uriFor 用错 volume」这一类错误，
            // 而那由 uriFor 自己的实现保证。
            result += PhotoItem(
                id = getLong(idIdx),
                volumeName = volume,
                displayName = getStringOrNull(nameIdx).orEmpty(),
                sizeBytes = getLongOrNull(sizeIdx) ?: 0L,
                takenAt = PhotoItem.resolveTakenAt(
                    dateTaken = getLongOrNull(takenIdx),
                    dateAdded = getLongOrNull(addedIdx),
                    dateModified = getLongOrNull(modifiedIdx),
                ),
                mimeType = getStringOrNull(mimeIdx),
                width = getIntOrNull(widthIdx) ?: 0,
                height = getIntOrNull(heightIdx) ?: 0,
            )
        }
        Log.d(TAG, "volume=$volume kept=${result.size} skippedTrashed=$skippedTrashed")
        return result
    }

    /**
     * `getColumnIndex` 对不存在的列返回 -1，直接传给 `getXxx` 会抛异常；列存在但值为 null
     * 也需要区分。三个 helper 把这四种组合（缺列 / null / 有值）收成「拿不到就是 null」。
     */
    private fun Cursor.getLongOrNull(index: Int): Long? =
        if (index >= 0 && !isNull(index)) getLong(index) else null

    private fun Cursor.getIntOrNull(index: Int): Int? =
        if (index >= 0 && !isNull(index)) getInt(index) else null

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (index >= 0 && !isNull(index)) getString(index) else null

    companion object {
        private const val TAG = "MediaStoreDataSource"

        /**
         * 这个 URI 是不是 MediaStore 的。给 Coil 的 `ThumbnailFetcher` 用：它需要判断
         * 「该不该我来处理」，但又不能自己 import MediaStore（见类注释的约束）。
         */
        fun isMediaUri(uri: Uri): Boolean =
            uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority == MediaStore.AUTHORITY

        /**
         * 刻意**不含 `VOLUME_NAME`**：值来自循环变量，见 mapRows。
         *
         * `IS_TRASHED` 必须取回来：回收站过滤是在 Kotlin 侧做的（见 mapRows 的注释），
         * 因为 MIUI 的 provider 不兑现 `QUERY_ARG_MATCH_TRASHED`。
         *
         * 注意 `DATE_TAKEN` 的实际列名是 `datetaken`（**没有下划线**），跟旁边几列的命名
         * 规则不一致。实测 `content query --projection date_taken` 会报 `Invalid column`，
         * 所以这里必须走常量，不能手写列名。
         */
        val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.IS_TRASHED,
        )
    }
}

/** 设备存储的总量/已用量，给范围页的存储卡用。 */
@Suppress("DEPRECATION") // getExternalStorageDirectory() 在 API 30+ 已废弃，但它仍是拿到「整机共享存储」StatFs 的可靠入口
fun Context.readStorageStats(): StorageStats = runCatching {
    val stat = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().absolutePath)
    val total = stat.blockCountLong * stat.blockSizeLong
    val free = stat.availableBlocksLong * stat.blockSizeLong
    StorageStats(totalBytes = total, usedBytes = (total - free).coerceAtLeast(0L))
}.getOrElse {
    // 拿不到就报 0，UI 侧退化成不显示百分比 —— 而不是崩掉，也不是显示一个编出来的数字。
    StorageStats(totalBytes = 0L, usedBytes = 0L)
}

data class StorageStats(val totalBytes: Long, val usedBytes: Long) {
    val fraction: Float get() = if (totalBytes <= 0L) 0f else (usedBytes.toDouble() / totalBytes).toFloat()
}
