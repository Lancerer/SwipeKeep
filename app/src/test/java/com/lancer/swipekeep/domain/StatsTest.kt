package com.lancer.swipekeep.domain

import com.lancer.swipekeep.domain.model.PhotoItem
import com.lancer.swipekeep.domain.model.SwipeDecision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 结算统计。这是**不可逆动作的输入** —— 结算页上的「删除 N 张」迟早会变成
 * `createTrashRequest` 的 URI 列表，所以这里的每个数都值得钉住。
 *
 * 特别是 `keptFraction`：它直接喂给 `StackedBarView`，而后者对 `NaN` 会抛异常。
 */
class StatsTest {

    private fun photo(id: Long, sizeBytes: Long) = PhotoItem(
        id = id,
        volumeName = "external_primary",
        displayName = "IMG_$id.jpg",
        sizeBytes = sizeBytes,
        takenAt = 1_700_000_000_000L + id,
        mimeType = "image/jpeg",
        width = 1080,
        height = 2340,
    )

    @Test
    fun `混合夹具：三种判定各算各的`() {
        val items = listOf(photo(1, 1_000), photo(2, 2_000), photo(3, 4_000), photo(4, 8_000))
        val session = SwipeSession(items = items)
            .swipe(items[0], SwipeDecision.KEEP, 0f, 0f)
            .swipe(items[1], SwipeDecision.DELETE, 0f, 0f)
            .swipe(items[2], SwipeDecision.UNDECIDED, 0f, 0f)
            .swipe(items[3], SwipeDecision.DELETE, 0f, 0f)

        val stats = Stats.computeSettlement(session)

        assertEquals("删了两张", 2, stats.deletedCount)
        assertEquals("删除体积只算删除那两张", 10_000L, stats.deletedBytes)
        assertEquals(1, stats.keptCount)
        assertEquals(1_000L, stats.keptBytes)
        assertEquals("待定单独计数，不并进任何一边", 1, stats.undecidedCount)
        assertEquals("待定也算看过", 4, stats.totalSeen)
        // 堆叠条的分母是「删除 + 保留」，待定不进分母
        assertEquals(1f / 3f, stats.keptFraction, 1e-6f)
        assertEquals(2f / 3f, stats.deleteFraction, 1e-6f)
    }

    @Test
    fun `全部保留：整条都是绿的，待删为空`() {
        val items = listOf(photo(1, 500), photo(2, 700))
        val session = SwipeSession(items = items)
            .swipe(items[0], SwipeDecision.KEEP, 0f, 0f)
            .swipe(items[1], SwipeDecision.KEEP, 0f, 0f)

        val stats = Stats.computeSettlement(session)

        assertEquals(0, stats.deletedCount)
        assertEquals(0L, stats.deletedBytes)
        assertEquals(2, stats.keptCount)
        assertEquals(1_200L, stats.keptBytes)
        assertEquals(1f, stats.keptFraction, 0f)
        assertEquals(0f, stats.deleteFraction, 0f)
    }

    /** 分母为 0。空会话进不到结算页，但纯函数不该靠调用方的自觉来避免除零。 */
    @Test
    fun `空会话不能让占比除零`() {
        val stats = Stats.computeSettlement(SwipeSession())

        assertEquals(0, stats.decidedCount)
        assertEquals(0f, stats.keptFraction, 0f)
        assertEquals(0f, stats.deleteFraction, 0f)
        assertEquals(0, stats.totalSeen)
        assertEquals(0, stats.deletedBytes)
    }

    /**
     * 重开 App 后恢复的上次会话：那些待删**不在撤销栈里**（[SwipeSession.detachedDeletes]），
     * 但结算页必须把它们算进去 —— 否则用户以为只删了本次的几张，实际提交时会多删一批。
     */
    @Test
    fun `恢复的待删也算进结算`() {
        val carried = photo(99, 5_000)
        val items = listOf(photo(1, 1_000))
        val session = SwipeSession(items = items, detachedDeletes = listOf(carried))
            .swipe(items[0], SwipeDecision.KEEP, 0f, 0f)

        val stats = Stats.computeSettlement(session)

        assertEquals(1, stats.deletedCount)
        assertEquals(5_000L, stats.deletedBytes)
        assertEquals(1, stats.keptCount)
        assertEquals(0.5f, stats.keptFraction, 1e-6f)
    }
}
