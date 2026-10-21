package com.lancer.swipekeep.domain.model

/** 滑卡顺序。切换排序只是反转同一个列表，不重新查询（见实施计划 §4.2）。 */
enum class SortOrder {
    NEWEST_FIRST,
    OLDEST_FIRST,
    ;

    fun toggled(): SortOrder = if (this == NEWEST_FIRST) OLDEST_FIRST else NEWEST_FIRST
}

/**
 * 一次清理的范围。
 *
 * 只有两种范围进 MVP：全部照片、某个月。截屏/相似照片/大视频在范围页里是置灰的
 * 「敬请期待」行，**不进这个类型** —— 没有实现的能力不该在类型系统里有位置。
 */
sealed interface CleanupScope {

    data class AllPhotos(val order: SortOrder = SortOrder.NEWEST_FIRST) : CleanupScope

    /** [year] / [month]（1–12）；[isUnknown] 表示「未知日期」桶 */
    data class ByMonth(
        val year: Int,
        val month: Int,
        val isUnknown: Boolean = false,
    ) : CleanupScope
}

/** 一月一桶。范围页每行一个。 */
data class MonthBucket(
    val year: Int,
    val month: Int,
    val isUnknown: Boolean,
    val count: Int,
    val bytes: Long,
) {
    val scope: CleanupScope.ByMonth
        get() = CleanupScope.ByMonth(year, month, isUnknown)
}
