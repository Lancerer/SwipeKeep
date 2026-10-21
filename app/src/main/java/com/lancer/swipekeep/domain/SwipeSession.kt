package com.lancer.swipekeep.domain

import com.lancer.swipekeep.domain.model.PhotoItem
import com.lancer.swipekeep.domain.model.SwipeDecision

/**
 * 一次清理会话的纯状态机：队列 + 游标 + 判定历史。
 *
 * 从 `SwipeViewModel` 里抽出来，是为了能单测它唯一那条要紧的不变量 ——
 * **滑 N 张再撤 N 张，牌堆位置与三项计数必须精确复原**。这条靠手滑验证不了：
 * 人没法滑几百张再回头去数。抽成不碰 Android 的纯 Kotlin 之后，一串断言就能钉住它。
 *
 * **不可变**：每次判定都产出一个新实例。ViewModel 把它放进 `MutableStateFlow` 时，
 * `copy` 出来的新实例天然是一个新发射值 —— 不必再自己维护版本号去骗 `StateFlow` 的相等性判断。
 *
 * 与 `SwipeCardStackView` 的分工：那边管「4 张卡怎么摆」，这里管「第几张、划掉了什么」。
 * 两者的游标由 `bind(items, startIndex)` 那一次调用对齐，之后各自前进一步。
 */
data class SwipeSession(
    val items: List<PhotoItem> = emptyList(),
    val index: Int = 0,
    val history: List<Decision> = emptyList(),
    /**
     * 「已经标记删除、但不在这个撤销栈里」的那些。
     *
     * 只有一处来源：**重开 App 后从磁盘恢复的上次会话**。上次的撤销栈没有存下来
     * （存了也没法用 —— 卡片堆要按 `exitX/exitY` 把卡片弹回来，而那需要上一次运行时
     * 的 View 状态），所以那些待删只能独立带进来，说清楚它们撤销不了。
     *
     * 与之对应的另一半是 [history] 里派生出来的待删 —— 那部分撤销一张就自动少一张。
     */
    val detachedDeletes: List<PhotoItem> = emptyList(),
) {

    /**
     * 一次判定的完整记录。
     *
     * 存 `exitX/exitY` 是为了撤销：卡片要从**它离开的那个位置**飞回来，
     * 而不是从屏幕正中弹出来（那样看着像凭空出现）。
     */
    data class Decision(
        val item: PhotoItem,
        val decision: SwipeDecision,
        val exitX: Float,
        val exitY: Float,
    )

    /** 已经划过多少张，也就是游标位置。 */
    val seen: Int get() = index

    /** 下一张该看的。看完了就是 null。 */
    val current: PhotoItem? get() = items.getOrNull(index)

    val lastDecision: Decision? get() = history.lastOrNull()

    /**
     * 已标记删除、但还没提交到系统回收站的那些。
     *
     * 主体**从 [history] 派生，不另开一个 store** —— 这样撤销一张 DELETE 就自动从待删里消失，
     * 两份状态不可能对不上。阶段 5 持久化、阶段 6 提交的都是它。
     *
     * 唯一不在历史里的是 [detachedDeletes]（重开 App 后恢复的上次会话）。
     */
    val pendingDeletes: List<PhotoItem>
        get() = detachedDeletes + history.filter { it.decision == SwipeDecision.DELETE }.map { it.item }

    val pendingDeleteBytes: Long get() = pendingDeletes.sumOf { it.sizeBytes }

    /**
     * 队列非空、且已经滑到头。
     *
     * 空队列**不算**「看完了」—— 「这个范围里没有照片」和「N 张都看完了」是两种终态，
     * 文案和后续动作都不同（前者建议换个范围，后者给结算入口）。
     */
    val finished: Boolean get() = items.isNotEmpty() && index >= items.size

    /**
     * 记下一次判定并前进一格。
     *
     * [item] 由调用方传入（卡片堆当时真正显示的那张），**不在这里按 `items[index]` 取**：
     * 相册可能在会话中途被系统改动，索引指向的位置未必还是用户刚看到的那张照片。
     */
    fun swipe(item: PhotoItem, decision: SwipeDecision, exitX: Float, exitY: Float): SwipeSession =
        copy(
            index = index + 1,
            history = trim(history + Decision(item, decision, exitX, exitY)),
        )

    /**
     * 回退一格：游标退一步、弹出最近一条判定。没有可撤销的返回 null。
     *
     * 返回 null 而不是抛异常 —— 撤销键在空栈时本来就是灰的，不可撤销是正常路径，不是错误。
     *
     * 游标与历史**各退各的**，不是「游标 = 历史长度」这种耦合写法：阶段 5 要恢复一次被
     * 存下来的会话，那时历史可能被裁剪过，而游标不会跟着退。
     */
    fun undo(): SwipeSession? {
        if (history.isEmpty()) return null
        return copy(
            index = (index - 1).coerceAtLeast(0),
            history = history.dropLast(1),
        )
    }

    companion object {

        /**
         * 撤销栈保留多少条。**故意留得比任何真实会话都长** —— 裁剪是一条只在极端会话里
         * 才会走到的路径，把它调小只会让「撤销能退回多远」变得取决于划了多少张，
         * 而那个上限用户看不见、也没法控制。
         *
         * 上限存在的原因只有一条：历史曾经无限增长，而它是**唯一还会随滑动增长的内存**
         * （队列是进入页面时的一份快照，不再变）。
         */
        const val MAX_HISTORY = 200

        /**
         * 裁剪撤销栈：只裁最前面的**非删除**条目。
         *
         * **绝不裁掉一条 DELETE**：`KEEP` / `UNDECIDED` 进了历史只是为了撤销（撤回来就是
         * 回到那一步），而 `DELETE` 是结算要提交的载荷 —— 裁掉一条就是静默少删一张照片，
         * 而用户看不到任何提示。所以裁剪遇到第一条 DELETE 就停手，宁可多留一点内存。
         *
         * 于是剩下的长度上界是 `MAX_HISTORY + 待删张数`，而待删张数本来就必须留着。
         */
        fun trim(entries: List<Decision>): List<Decision> {
            if (entries.size <= MAX_HISTORY) return entries
            var from = 0
            while (from < entries.size - MAX_HISTORY && entries[from].decision != SwipeDecision.DELETE) {
                from++
            }
            // subList 是原表的视图，不收成新表会让旧的历史跟着一起活着。
            return if (from == 0) entries else entries.subList(from, entries.size).toList()
        }
    }
}
