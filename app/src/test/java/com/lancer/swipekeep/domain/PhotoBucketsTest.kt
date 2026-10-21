package com.lancer.swipekeep.domain

import com.lancer.swipekeep.domain.model.CleanupScope
import com.lancer.swipekeep.domain.model.PhotoItem
import com.lancer.swipekeep.domain.model.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 月份分桶与排序。
 *
 * 时区固定成 [ZONE]，否则这些测试在别的时区的机器上会随机失败 ——
 * 「1 月 1 日 00:30 北京时间」在 UTC 下是上一年的 12 月 31 日，
 * 而这正是分桶要处理的最容易错的边界。
 */
class PhotoBucketsTest {

    @Test
    fun `按月分桶，新的在前`() {
        val items = listOf(
            photo(id = 1, at = at(2024, 3, 15)),
            photo(id = 2, at = at(2024, 5, 2)),
            photo(id = 3, at = at(2024, 3, 28)),
        )
        val buckets = PhotoBuckets.bucketByMonth(items, ZONE)

        assertEquals(listOf(2024 to 5, 2024 to 3), buckets.map { it.year to it.month })
        assertEquals(1, buckets[0].count)
        assertEquals(2, buckets[1].count)
    }

    @Test
    fun `无时间戳的照片归入未知日期桶，且永远排在最后`() {
        val items = listOf(
            photo(id = 1, at = PhotoItem.UNKNOWN_TIME),
            photo(id = 2, at = at(2024, 1, 1)),
        )
        val buckets = PhotoBuckets.bucketByMonth(items, ZONE)

        assertEquals(2, buckets.size)
        assertEquals(true, buckets.last().isUnknown)
        assertEquals(1, buckets.last().count)
    }

    @Test
    fun `分桶后张数之和等于总数`() {
        // 这条是自检：静默丢照片（比如把 null 时间戳的行过滤掉）会在这里暴露。
        val items = listOf(
            photo(id = 1, at = at(2023, 12, 31)),
            photo(id = 2, at = at(2024, 1, 1)),
            photo(id = 3, at = PhotoItem.UNKNOWN_TIME),
            photo(id = 4, at = at(2024, 1, 20)),
        )
        val buckets = PhotoBuckets.bucketByMonth(items, ZONE)
        assertEquals(items.size, buckets.sumOf { it.count })
    }

    @Test
    fun `跨年边界按本地时区切分，不会把元旦算到上一年`() {
        // 北京时间 2024-01-01 00:30 在 UTC 下是 2023-12-31 16:30。
        // 若用 UTC 分桶，这张照片会出现在「2023 年 12 月」里 —— 用户会觉得日期错了。
        val items = listOf(photo(id = 1, at = at(2024, 1, 1, hour = 0, minute = 30)))
        val buckets = PhotoBuckets.bucketByMonth(items, ZONE)

        assertEquals(2024, buckets.single().year)
        assertEquals(1, buckets.single().month)
    }

    @Test
    fun `未知日期桶的月份为 0，不会和真实的 0 年 0 月混淆`() {
        val buckets = PhotoBuckets.bucketByMonth(listOf(photo(id = 1, at = PhotoItem.UNKNOWN_TIME)), ZONE)
        val unknown = buckets.single()
        assertEquals(true, unknown.isUnknown)
        assertEquals(PhotoBuckets.UNKNOWN_YEAR, unknown.year)
        assertEquals(PhotoBuckets.UNKNOWN_MONTH, unknown.month)
    }

    @Test
    fun `体积按桶累加`() {
        val items = listOf(
            photo(id = 1, at = at(2024, 4, 1), size = 100),
            photo(id = 2, at = at(2024, 4, 9), size = 250),
        )
        val bucket = PhotoBuckets.bucketByMonth(items, ZONE).single()
        assertEquals(350L, bucket.bytes)
    }

    @Test
    fun `排序：最近优先`() {
        val items = listOf(
            photo(id = 1, at = at(2024, 1, 1)),
            photo(id = 2, at = at(2024, 6, 1)),
            photo(id = 3, at = at(2023, 6, 1)),
        )
        val sorted = PhotoBuckets.sort(items, SortOrder.NEWEST_FIRST)
        assertEquals(listOf(2L, 1L, 3L), sorted.map { it.id })
    }

    @Test
    fun `排序：最旧优先`() {
        val items = listOf(
            photo(id = 1, at = at(2024, 1, 1)),
            photo(id = 2, at = at(2024, 6, 1)),
            photo(id = 3, at = at(2023, 6, 1)),
        )
        val sorted = PhotoBuckets.sort(items, SortOrder.OLDEST_FIRST)
        assertEquals(listOf(3L, 1L, 2L), sorted.map { it.id })
    }

    @Test
    fun `连拍同秒的照片靠 id 兜底，顺序稳定`() {
        // 没有这个兜底，同一秒的多张照片在两次扫描之间顺序可能不同，
        // 于是「撤销回到第 N 张」会撤回另一张。
        val sameSecond = at(2024, 2, 14, hour = 12, minute = 0)
        val items = listOf(
            photo(id = 30, at = sameSecond),
            photo(id = 10, at = sameSecond),
            photo(id = 20, at = sameSecond),
        )
        assertEquals(listOf(10L, 20L, 30L), PhotoBuckets.sort(items, SortOrder.OLDEST_FIRST).map { it.id })
    }

    @Test
    fun `按范围过滤：某个月只留该月的照片`() {
        val items = listOf(
            photo(id = 1, at = at(2024, 4, 1)),
            photo(id = 2, at = at(2024, 5, 1)),
            photo(id = 3, at = at(2024, 4, 30)),
        )
        val filtered = PhotoBuckets.filterFor(items, CleanupScope.ByMonth(2024, 4), ZONE)
        assertEquals(listOf(1L, 3L), filtered.map { it.id }.sorted())
    }

    @Test
    fun `按范围过滤：未知日期范围只留没有时间戳的`() {
        val items = listOf(
            photo(id = 1, at = at(2024, 4, 1)),
            photo(id = 2, at = PhotoItem.UNKNOWN_TIME),
        )
        val filtered = PhotoBuckets.filterFor(items, CleanupScope.ByMonth(0, 0, isUnknown = true), ZONE)
        assertEquals(listOf(2L), filtered.map { it.id })
    }

    @Test
    fun `按范围过滤：全部照片返回全量并排好序`() {
        val items = listOf(
            photo(id = 1, at = at(2024, 4, 1)),
            photo(id = 2, at = at(2024, 5, 1)),
        )
        val filtered = PhotoBuckets.filterFor(items, CleanupScope.AllPhotos(SortOrder.NEWEST_FIRST), ZONE)
        assertEquals(2, filtered.size)
        assertEquals(listOf(2L, 1L), filtered.map { it.id })
    }

    @Test
    fun `空列表不崩，返回空桶`() {
        assertEquals(emptyList<Any>(), PhotoBuckets.bucketByMonth(emptyList(), ZONE))
    }

    private fun at(year: Int, month: Int, day: Int = 1, hour: Int = 12, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(ZONE).toInstant().toEpochMilli()

    private fun photo(id: Long, at: Long, size: Long = 0L) = PhotoItem(
        id = id,
        volumeName = "external_primary",
        displayName = "p$id.jpg",
        sizeBytes = size,
        takenAt = at,
        mimeType = "image/jpeg",
        width = 100,
        height = 100,
    )

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}
