package com.lancer.swipekeep.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分块提交状态机。这是**不可逆动作的调度器** —— 算错一次就是少删一批或多弹一个框，
 * 而两条都只能靠单测钉住（真机上一个 1000 张的批次要人工点几十次确认框）。
 *
 * 夹具一律用 `chunkSize = 5`：与真机验证多弹窗时用的临时值一致，
 * 而 20 张走完 4 块正好覆盖「顺序 / 取消 / 部分」三条路径。
 */
class TrashCommitterTest {

    private fun committer(total: Int = 20, chunkSize: Int = 5) =
        TrashCommitter(total = total, chunkSize = chunkSize)

    @Test
    fun `默认块大小不超过文档上限的一半`() {
        assertTrue(
            "留一半余量：块大小超过上限的 1/4 就没资格叫「余量」",
            TrashCommitter.DEFAULT_CHUNK * 2 <= TrashCommitter.MAX_URIS_PER_REQUEST,
        )
    }

    @Test
    fun `空队列直接是完成态`() {
        val c = TrashCommitter(total = 0)
        assertEquals(TrashCommitter.State.Done(0), c.state)
        assertEquals(0, c.batchCount)
        assertEquals(0, c.remaining)
    }

    @Test
    fun `20 张分 4 批，第一批从 0 开始`() {
        val c = committer()
        assertEquals(4, c.batchCount)
        assertEquals(TrashCommitter.State.Idle, c.state)

        val started = c.begin()
        assertEquals(TrashCommitter.State.Awaiting(chunkIndex = 1, from = 0, count = 5), started.state)
    }

    @Test
    fun `逐块确认走到底：committed 是连续前缀`() {
        var c = committer().begin()
        // 每块都是 5 张，四块走完
        repeat(4) {
            c = c.onChunkDone()
        }
        assertEquals(TrashCommitter.State.Done(20), c.state)
        assertEquals(20, c.committed)
        assertEquals(0, c.remaining)
    }

    @Test
    fun `最后一块不满时只提交剩下的张数`() {
        // 23 张按 10 一块：10 + 10 + 3
        var c = TrashCommitter(total = 23, chunkSize = 10).begin()
        assertEquals(3, c.batchCount)
        assertEquals(TrashCommitter.State.Awaiting(1, 0, 10), c.state)

        c = c.onChunkDone().onChunkDone()
        assertEquals(TrashCommitter.State.Awaiting(chunkIndex = 3, from = 20, count = 3), c.state)

        c = c.onChunkDone()
        assertEquals(TrashCommitter.State.Done(23), c.state)
    }

    /** 取消第 2 块：第 1 块已经真删了，报告「已处理 5 / 还剩 15」，并且不再弹第 3 块。 */
    @Test
    fun `第二块取消：整批停止，只报部分结果`() {
        var c = committer().begin().onChunkDone()
        assertEquals(TrashCommitter.State.Awaiting(2, 5, 5), c.state)

        c = c.onChunkCancelled()

        assertEquals(TrashCommitter.State.Partial(committed = 5, remaining = 15, cancelledAt = 2), c.state)
        // 状态机里已经没有 Awaiting 了，所以调用方不会再发起第 3 块
        assertTrue("取消后必须停在 Partial", c.state !is TrashCommitter.State.Awaiting)
    }

    @Test
    fun `第一块就取消：一张都没动`() {
        val c = committer().begin().onChunkCancelled()
        assertEquals(TrashCommitter.State.Partial(0, 20, 1), c.state)
    }

    /** 「继续处理剩余的 N 张」：从 committed 处接着切，不从头再来。 */
    @Test
    fun `Partial 之后可以接着提交剩下的`() {
        var c = committer().begin().onChunkDone().onChunkCancelled()
        c = c.begin()
        assertEquals(TrashCommitter.State.Awaiting(chunkIndex = 2, from = 5, count = 5), c.state)

        repeat(3) { c = c.onChunkDone() }
        assertEquals(TrashCommitter.State.Done(20), c.state)
    }

    @Test
    fun `块太大时减半重试同一位置`() {
        var c = committer(chunkSize = 8).begin()
        assertEquals(TrashCommitter.State.Awaiting(1, 0, 8), c.state)

        c = c.onChunkTooLarge()
        assertEquals("块减半", 4, c.chunkSize)
        assertEquals("位置不动，张数变小", TrashCommitter.State.Awaiting(1, 0, 4), c.state)

        c = c.onChunkTooLarge()
        assertEquals(2, c.chunkSize)
        assertEquals(TrashCommitter.State.Awaiting(1, 0, 2), c.state)
    }

    /** 缩到 1 张还被拒就不是块大小的问题了 —— 停下，不要无限重试。 */
    @Test
    fun `块缩到 1 张仍失败就停在 Partial`() {
        var c = committer(chunkSize = 2).begin()
        c = c.onChunkTooLarge() // 1
        assertEquals(1, c.chunkSize)
        c = c.onChunkTooLarge()
        assertEquals(TrashCommitter.State.Partial(0, 20, 1), c.state)
    }

    @Test
    fun `进程被回收后恢复：不自动重弹，等用户点继续`() {
        val restored = TrashCommitter.restored(total = 20, chunkSize = 5, committed = 10)

        assertEquals(10, restored.committed)
        assertEquals(TrashCommitter.State.Partial(10, 10, -1), restored.state)

        // 用户点「继续」才会重新进入 Awaiting
        assertEquals(TrashCommitter.State.Awaiting(3, 10, 5), restored.begin().state)
    }

    @Test
    fun `恢复时越界的 committed 会被夹回范围`() {
        assertEquals(0, TrashCommitter.restored(20, 5, -3).committed)
        assertEquals(20, TrashCommitter.restored(20, 5, 99).committed)
        // committed 已经是全部 → 直接完成态，不回到 Idle
        assertTrue(TrashCommitter.restored(20, 5, 99).state is TrashCommitter.State.Done)
    }

    @Test
    fun `已完成之后再 begin 不会倒回去`() {
        var c = committer(total = 5).begin()
        c = c.onChunkDone()
        assertEquals(TrashCommitter.State.Done(5), c.state)
        assertEquals(TrashCommitter.State.Done(5), c.begin().state)
    }
}
