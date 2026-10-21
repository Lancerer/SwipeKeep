package com.lancer.swipekeep.data.media

/**
 * 「把待删队列分批移入系统回收站」的纯状态机。**不碰 Android**，能在 JVM 上单测。
 *
 * ## 为什么要分块
 *
 * Android 的 `MediaStore.createTrashRequest` 一次调用**最多 2000 个 URI**，超了直接抛
 * `IllegalArgumentException`。而「一次调用」= 一个 `PendingIntent` = **一个系统确认框**，
 * 所以 N 块就是 N 个连续弹窗 —— 这是 API 决定的，躲不开（实施计划 §4.4）。
 *
 * 块大小刻意留在一半余量（[DEFAULT_CHUNK] = 1000 vs [MAX_URIS_PER_REQUEST] = 2000）：
 * 真机测试多弹窗路径时把 [chunkSize] 临时调成 5，用 20 张照片就能走完
 * 「多块顺序 / 中途取消 / 部分结果」三条路径。
 *
 * ## 为什么是「位置」而不是「URI 列表」
 *
 * 状态里只存**偏移量**。URI 由调用方按 `items[from until from+count]` 现算 ——
 * 这样整份队列不必进状态机，`SavedStateHandle` 里只需要存三个 Int，
 * 系统对话框期间进程被回收也恢复得回来。
 *
 * ## 顺序即前缀
 *
 * 已提交的一定是队列的**连续前缀**：第 3 块没确认，第 4 块就不会开始。
 * 所以「还剩多少」= `total - committed`，不需要记录哪些成功哪些失败。
 */
data class TrashCommitter(
    /** 这一批一共要提交多少张。 */
    val total: Int,
    /** 一次系统对话框覆盖多少张。真机测多弹窗时临时改成 5。 */
    val chunkSize: Int = DEFAULT_CHUNK,
    /** 已成功移入回收站的张数（从队首算起的连续前缀）。 */
    val committed: Int = 0,
    val state: State = if (total <= 0) State.Done(0) else State.Idle,
) {

    sealed interface State {
        /** 还没开始。用户点了「确认删除」但（或还没）发出第一个系统对话框。 */
        data object Idle : State

        /**
         * 正在等系统对话框的结果。
         *
         * [chunkIndex] 是 1 起的人类序号，只用于文案（「第 2 批，共 4 批」）；
         * [from] / [count] 才是真正的队列切片。
         */
        data class Awaiting(val chunkIndex: Int, val from: Int, val count: Int) : State

        /**
         * 中途停下（用户取消，或块已经缩到 1 张仍然被 provider 拒绝）。
         *
         * [cancelledAt] 是停下时那一批的序号；`-1` 表示「不是取消，是进程在对话框期间
         * 被回收后恢复的」—— 那种情况下我们不知道用户按了什么，只能再问一次。
         */
        data class Partial(val committed: Int, val remaining: Int, val cancelledAt: Int) : State

        /** 全部提交完。 */
        data class Done(val committed: Int) : State
    }

    val remaining: Int get() = (total - committed).coerceAtLeast(0)

    /** 一共要弹几次系统对话框。0 = 没有可提交的。 */
    val batchCount: Int get() = if (total <= 0) 0 else (total + chunkSize - 1) / chunkSize

    /**
     * 开始（或从中断处接着）提交。已经是 [State.Done] 就原样返回。
     *
     * 从 [State.Partial] 进来时 `committed` 已经指向「下一张没提交的」，
     * 所以这里不需要额外参数 —— 「继续处理剩余的 N 张」和「第一次提交」是同一条路径。
     */
    fun begin(): TrashCommitter = when {
        committed >= total -> copy(state = State.Done(committed))
        else -> copy(state = awaitingAt(committed, chunkIndexFor(committed)))
    }

    /** 当前这一批用户点了「允许」。 */
    fun onChunkDone(): TrashCommitter {
        val awaiting = state as? State.Awaiting ?: return this
        val next = (committed + awaiting.count).coerceAtMost(total)
        return if (next >= total) {
            copy(committed = next, state = State.Done(next))
        } else {
            copy(committed = next, state = awaitingAt(next, awaiting.chunkIndex + 1))
        }
    }

    /**
     * 当前这一批用户点了「取消」。
     *
     * **停止整批**，不要接着弹下一块 —— 用户已经叫停了，再弹一个确认框是最烦人的那种行为。
     * 已经提交的前几块是真删了的，所以返回 [State.Partial] 而不是「什么都没发生」。
     */
    fun onChunkCancelled(): TrashCommitter {
        val awaiting = state as? State.Awaiting ?: return this
        return copy(state = State.Partial(committed, remaining, awaiting.chunkIndex))
    }

    /**
     * provider 用 `IllegalArgumentException` 告诉我们「这批太多了」—— 块减半重试同一位置。
     *
     * 减到 1 张仍然失败就不再重试：那不是块大小的问题，重试多少次都一样，
     * 只能停下并把实情告诉用户（[State.Partial]）。
     */
    fun onChunkTooLarge(): TrashCommitter {
        val awaiting = state as? State.Awaiting ?: return this
        if (chunkSize <= 1) {
            return copy(state = State.Partial(committed, remaining, awaiting.chunkIndex))
        }
        val nextSize = (chunkSize / 2).coerceAtLeast(1)
        return copy(
            chunkSize = nextSize,
            state = State.Awaiting(
                chunkIndex = awaiting.chunkIndex,
                from = awaiting.from,
                count = minOf(nextSize, total - awaiting.from),
            ),
        )
    }

    private fun awaitingAt(from: Int, chunkIndex: Int): State.Awaiting =
        State.Awaiting(chunkIndex, from, minOf(chunkSize, total - from))

    private fun chunkIndexFor(position: Int): Int = position / chunkSize + 1

    companion object {
        /**
         * Android 文档给的硬上限（targetSdk 36）。超了 `createTrashRequest` 抛
         * `IllegalArgumentException`，而不是悄悄截断。
         */
        const val MAX_URIS_PER_REQUEST = 2000

        /** 默认块大小。留一半余量，同时也让「1000 张以内一次弹窗」成为常态。 */
        const val DEFAULT_CHUNK = 1000

        /**
         * 进程在系统对话框期间被回收后恢复用。
         *
         * 刻意**不自动重新弹窗**：我们不知道那次对话框到底被确认了没有，自动重弹可能
         * 让用户对着一个已经生效的操作再按一次。恢复成 [State.Partial]，
         * 由用户主动点「继续」来决定。
         */
        fun restored(total: Int, chunkSize: Int, committed: Int): TrashCommitter {
            val safeChunk = chunkSize.coerceAtLeast(1)
            val safeCommitted = committed.coerceIn(0, total)
            val base = TrashCommitter(total = total, chunkSize = safeChunk, committed = safeCommitted)
            return when {
                // 上次已经全部提交完（清理完 store 之后进程才被杀）
                total > 0 && safeCommitted >= total -> base.copy(state = State.Done(safeCommitted))
                safeCommitted > 0 -> base.copy(
                    state = State.Partial(safeCommitted, total - safeCommitted, -1),
                )
                else -> base
            }
        }
    }
}
