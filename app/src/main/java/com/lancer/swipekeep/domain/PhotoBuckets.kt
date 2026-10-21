package com.lancer.swipekeep.domain

import com.lancer.swipekeep.domain.model.CleanupScope
import com.lancer.swipekeep.domain.model.MonthBucket
import com.lancer.swipekeep.domain.model.PhotoItem
import com.lancer.swipekeep.domain.model.SortOrder
import java.time.Instant
import java.time.ZoneId

/**
 * 排序与月份分桶，**全部在 Kotlin 侧做**，SQL 只负责把元数据取回来。
 *
 * 为什么不在 SQL 里排序/分组（实施计划 §4.2）：
 * 1. 各 OEM 的 MediaProvider 对 `DATE_TAKEN IS NULL` 的排序行为不一致 —— SQL 排序会得到
 *    与小米相册对不上的顺序，而这种偏差在不同机型上表现不同，无法靠调 SQL 修好。
 * 2. 一次全量扫描就能同时产出每月张数与体积，比「先查列表再逐个查 COUNT」少 N 次查询。
 *
 * 纯 Kotlin、无 Android 依赖 —— 可以在 JVM 上直接单测。[zone] 显式传参就是为了这一点：
 * 单测里传固定时区，结果不随运行机器的时区漂移。
 */
object PhotoBuckets {

    const val UNKNOWN_YEAR = 0
    const val UNKNOWN_MONTH = 0

    /**
     * 排序。`takenAt` 相同时用 `id` 兜底 —— 没有这个兜底，同秒连拍的照片在不同次扫描里
     * 顺序可能不同，会让「撤销回到第 N 张」对不上。
     */
    fun sort(items: List<PhotoItem>, order: SortOrder): List<PhotoItem> {
        val byTimeThenId = compareBy<PhotoItem> { it.takenAt }.thenBy { it.id }
        return when (order) {
            SortOrder.OLDEST_FIRST -> items.sortedWith(byTimeThenId)
            SortOrder.NEWEST_FIRST -> items.sortedWith(byTimeThenId.reversed())
        }
    }

    /**
     * 按月分桶。返回顺序：**新的在前，「未知日期」永远在最后**。
     *
     * 无时间戳的照片归入「未知日期」桶而不是被丢掉 —— 静默丢数据会让「每月张数之和
     * = 总数」这个自检失效，而用户会以为照片不见了。
     */
    fun bucketByMonth(items: List<PhotoItem>, zone: ZoneId = ZoneId.systemDefault()): List<MonthBucket> {
        val known = LinkedHashMap<Long, Accumulator>()
        var unknownCount = 0
        var unknownBytes = 0L

        for (item in items) {
            if (item.takenAt <= PhotoItem.UNKNOWN_TIME) {
                unknownCount++
                unknownBytes += item.sizeBytes
                continue
            }
            val local = Instant.ofEpochMilli(item.takenAt).atZone(zone).toLocalDate()
            val key = local.year.toLong() * 100L + local.monthValue
            val acc = known.getOrPut(key) { Accumulator(local.year, local.monthValue) }
            acc.count++
            acc.bytes += item.sizeBytes
        }

        val buckets = known.values
            .map { MonthBucket(it.year, it.month, isUnknown = false, count = it.count, bytes = it.bytes) }
            // 键是 year*100+month，降序即「新的在前」
            .sortedByDescending { it.year.toLong() * 100L + it.month }

        return if (unknownCount == 0) {
            buckets
        } else {
            buckets + MonthBucket(
                year = UNKNOWN_YEAR,
                month = UNKNOWN_MONTH,
                isUnknown = true,
                count = unknownCount,
                bytes = unknownBytes,
            )
        }
    }

    /** 取某个范围对应的照片列表。[CleanupScope.AllPhotos] 直接返回排好序的全量。 */
    fun filterFor(
        items: List<PhotoItem>,
        scope: CleanupScope,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<PhotoItem> = when (scope) {
        is CleanupScope.AllPhotos -> sort(items, scope.order)

        is CleanupScope.ByMonth -> items.filter { item ->
            if (item.takenAt <= PhotoItem.UNKNOWN_TIME) {
                scope.isUnknown
            } else {
                val local = Instant.ofEpochMilli(item.takenAt).atZone(zone).toLocalDate()
                !scope.isUnknown && local.year == scope.year && local.monthValue == scope.month
            }
        }
    }

    private class Accumulator(val year: Int, val month: Int) {
        var count: Int = 0
        var bytes: Long = 0L
    }
}
