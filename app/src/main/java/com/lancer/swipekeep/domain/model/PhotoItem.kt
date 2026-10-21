package com.lancer.swipekeep.domain.model

/**
 * 相册里的一张照片。
 *
 * **身份是 `(volumeName, id)` 而不是 `id`** —— `_ID` 只在单个 volume 内唯一，
 * 外置 SD 卡与内置存储可以出现相同的 id。全 App 的相等性、集合运算、撤销栈
 * 都依赖这一点（`data class` 天然满足）。
 *
 * 刻意不持 `Uri`：`domain/` 必须能在纯 JVM 上跑单测，`Uri` 是 Android 类型。
 * URI 由 [com.lancer.swipekeep.data.media.MediaStoreDataSource] 按 volume 现算。
 */
data class PhotoItem(
    val id: Long,
    val volumeName: String,
    val displayName: String,
    val sizeBytes: Long,
    /** 归一化后的毫秒时间戳，见 [resolveTakenAt]；拿不到时间时为 [UNKNOWN_TIME] */
    val takenAt: Long,
    val mimeType: String?,
    val width: Int,
    val height: Int,
) {

    /** 全 App 的身份。集合运算、持久化、对账都用它，不用整条记录。 */
    val identity: Identity get() = Identity(volumeName, id)

    /**
     * 一张照片的身份：`(volumeName, id)`。
     *
     * 单独一个类型而不是 `Pair`：持久化的格式、跟相册对账的键都长在它身上，
     * 有一个名字才好在签名里说清「这里要的是身份，不是整条记录」。
     */
    data class Identity(val volumeName: String, val id: Long)

    companion object {
        /** 三个时间列全为空时的兜底值，归入「未知日期」桶 */
        const val UNKNOWN_TIME = 0L

        /**
         * 把 MediaStore 的三个时间列归一化成毫秒。**这是全 App 唯一一处做这件事的地方。**
         *
         * 两个坑：
         * 1. `DATE_TAKEN` 在大量真实照片上是 null（OEM 截图、下载、微信保存图都没有 EXIF 拍摄时间）
         * 2. `DATE_TAKEN` 单位是**毫秒**，而 `DATE_ADDED` / `DATE_MODIFIED` 单位是**秒**
         *
         * 顺序：DATE_TAKEN → DATE_ADDED×1000 → DATE_MODIFIED×1000 → 0（未知）
         * 用 `> 0` 而不是 `!= null` 判空：MediaStore 里也真实存在 0 这种「有列无值」的行。
         */
        fun resolveTakenAt(dateTaken: Long?, dateAdded: Long?, dateModified: Long?): Long =
            dateTaken?.takeIf { it > 0L }
                ?: dateAdded?.takeIf { it > 0L }?.times(1000L)
                ?: dateModified?.takeIf { it > 0L }?.times(1000L)
                ?: UNKNOWN_TIME
    }
}
