package com.lancer.swipekeep.domain

import com.lancer.swipekeep.domain.model.SwipeDecision

/**
 * 结算页要显示的那几个数。
 *
 * 刻意**不含任何格式化后的文案**（没有「2.3 GB」也没有「5 月」）—— 格式化要
 * `Context.getString`，而这一层是纯 Kotlin、要能在 JVM 上单测。单位换算与月份名
 * 由 UI 层拼（`Formatters`）。
 */
data class SettlementStats(
    /** 已标记删除、但**还没提交**到系统回收站的张数与体积。 */
    val deletedCount: Int = 0,
    val deletedBytes: Long = 0L,
    val keptCount: Int = 0,
    val keptBytes: Long = 0L,
    val undecidedCount: Int = 0,
    /** 这一轮看完多少张。待定也算看过。 */
    val totalSeen: Int = 0,
) {

    /** 「删除 + 保留」。待定不参与堆叠条 —— 它既没删也没留，画进哪一边都是错的。 */
    val decidedCount: Int get() = keptCount + deletedCount

    /**
     * 堆叠条里绿色（保留）的占比。
     *
     * 分母为 0 时返回 0 而不是崩掉、也不是 `NaN`：`NaN` 传给 `StackedBarView` 会让
     * `coerceIn` 抛异常。空会话本来就进不到结算页（那个范围里没照片会在滑卡页就停住），
     * 但纯函数不该靠「调用方不会这么调」来保证正确性。
     */
    val keptFraction: Float
        get() = if (decidedCount <= 0) 0f else keptCount.toFloat() / decidedCount

    val deleteFraction: Float get() = if (decidedCount <= 0) 0f else 1f - keptFraction
}

/**
 * 结算统计的唯一算法。
 *
 * 与实施计划 §4.5 的签名（`computeSettlement(items, decisions)`）有一处偏差：
 * 这里收的是整个 [SwipeSession] 而不是两个列表。理由是**待删集合不是从历史直接派生的** ——
 * 它还要算上 [SwipeSession.detachedDeletes]（重开 App 后恢复的上次会话）。
 * 拆成两个参数传，就给了调用方「传错一对列表」的机会，而算错的是「要删多少张」这种
 * 不可逆动作的输入。
 */
object Stats {

    fun computeSettlement(session: SwipeSession): SettlementStats {
        var keptCount = 0
        var keptBytes = 0L
        var undecided = 0
        for (entry in session.history) {
            when (entry.decision) {
                SwipeDecision.KEEP -> {
                    keptCount++
                    keptBytes += entry.item.sizeBytes
                }

                SwipeDecision.UNDECIDED -> undecided++

                // 删除那一侧的真相源是 pendingDeletes，不是历史：历史可能被裁剪过。
                SwipeDecision.DELETE -> Unit
            }
        }

        val pending = session.pendingDeletes
        return SettlementStats(
            deletedCount = pending.size,
            deletedBytes = pending.sumOf { it.sizeBytes },
            keptCount = keptCount,
            keptBytes = keptBytes,
            undecidedCount = undecided,
            totalSeen = session.seen,
        )
    }
}
