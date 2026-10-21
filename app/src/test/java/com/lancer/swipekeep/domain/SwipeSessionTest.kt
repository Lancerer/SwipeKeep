package com.lancer.swipekeep.domain

import com.lancer.swipekeep.domain.model.PhotoItem
import com.lancer.swipekeep.domain.model.SwipeDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SwipeSession] 的不变量。
 *
 * 重点是那条只能靠断言、不能靠手滑验证的：**滑 N 张再撤 N 张之后，
 * 游标、已看张数、待删张数与待删字节全部回到起点** —— 四个量一起复原，
 * 才算「撤销真的把状态退回去了」，只对上游标而历史没退是看不到的。
 */
class SwipeSessionTest {

    private fun photo(id: Long, sizeBytes: Long = 1_000L) = PhotoItem(
        id = id,
        volumeName = "external_primary",
        displayName = "IMG_$id.jpg",
        sizeBytes = sizeBytes,
        takenAt = 1_700_000_000_000L + id,
        mimeType = "image/jpeg",
        width = 1080,
        height = 2340,
    )

    private fun queue(count: Int, sizeBytes: Long = 1_000L) =
        (1..count).map { photo(it.toLong(), sizeBytes) }

    @Test
    fun `新会话停在第一张`() {
        val session = SwipeSession(items = queue(3))

        assertEquals(0, session.seen)
        assertEquals(photo(1), session.current)
        assertNull(session.lastDecision)
        assertTrue(session.pendingDeletes.isEmpty())
        assertFalse(session.finished)
    }

    @Test
    fun `判定一次就前进一格并记下离开位置`() {
        val session = SwipeSession(items = queue(3))

        val after = session.swipe(photo(1), SwipeDecision.DELETE, exitX = -450f, exitY = 80f)

        assertEquals(1, after.seen)
        assertEquals(photo(2), after.current)
        assertEquals(-450f, after.lastDecision!!.exitX, 0f)
        assertEquals(80f, after.lastDecision!!.exitY, 0f)
    }

    /**
     * 这条是本阶段唯一真正的验收标准：手滑做不到「滑 200 张再撤 200 张然后核对四个计数」。
     */
    @Test
    fun `滑 N 张撤 N 张之后四个计数精确复原`() {
        val items = queue(20, sizeBytes = 2_500L)
        val start = SwipeSession(items = items)

        // 交错着滑：7 张删除、13 张保留，另外掺两种不对称的位移
        var session = start
        repeat(20) { i ->
            val decision = if (i % 3 == 0) SwipeDecision.DELETE else SwipeDecision.KEEP
            session = session.swipe(items[i], decision, exitX = -i * 10f, exitY = i * 5f)
        }

        assertEquals(20, session.seen)
        assertTrue(session.finished)
        // 0,3,6,9,12,15,18 → 7 张
        assertEquals(7, session.pendingDeletes.size)
        assertEquals(7 * 2_500L, session.pendingDeleteBytes)

        repeat(20) { session = session.undo()!! }

        assertEquals("游标要退回来", 0, session.seen)
        assertEquals("历史要清空", emptyList<SwipeSession.Decision>(), session.history)
        assertEquals("待删集合要清空", 0, session.pendingDeletes.size)
        assertEquals("待删字节要归零", 0L, session.pendingDeleteBytes)
        assertEquals("下一张要回到第一张", items[0], session.current)
        assertFalse(session.finished)
        // 四组量都回到起点之后，整条会话应当与新建的那个**完全相等**（data class 的用处）
        assertEquals(start, session)
    }

    /**
     * `undo()` 返回的是**回退之后**的会话，不是被弹掉的那条记录 ——
     * 调用方（`SwipeViewModel.undo`）先读 `lastDecision` 拿 `exitX/exitY`，再退，
     * 顺序反了就会飘出一张位置不对的卡。这里把这个两步契约钉住。
     */
    @Test
    fun `撤销只回退一格`() {
        // 先删一张、再留一张，于是「撤销」撤销的是那张保留的 —— 这样才能看出
        // 待删集合是**跟着历史派生**的，而不是撤销什么都会掉出来一张待删。
        val session = SwipeSession(items = queue(3))
            .swipe(photo(1), SwipeDecision.DELETE, -400f, 90f)
            .swipe(photo(2), SwipeDecision.KEEP, 300f, 40f)

        val doomed = session.lastDecision!!
        val undone = session.undo()!!

        assertEquals("被撤销的是最后划的那张", photo(2), doomed.item)
        assertEquals("撤销要把它离开的位置带回来", 300f, doomed.exitX, 0f)
        assertEquals(1, undone.seen)
        assertEquals(1, undone.history.size)
        assertEquals(photo(1), undone.lastDecision!!.item)
        assertEquals("撤销一张保留不影响待删集合", listOf(photo(1)), undone.pendingDeletes)
    }

    @Test
    fun `空历史撤销返回 null 而不是抛异常`() {
        assertNull(SwipeSession(items = queue(3)).undo())
    }

    @Test
    fun `暂不确定也算划过但不算待删`() {
        val session = SwipeSession(items = queue(3))
            .swipe(photo(1), SwipeDecision.UNDECIDED, 0f, -700f)

        assertEquals(1, session.seen)
        assertTrue(session.pendingDeletes.isEmpty())
        assertEquals(0L, session.pendingDeleteBytes)
    }

    @Test
    fun `滑完最后一张才叫看完`() {
        var session = SwipeSession(items = queue(2))

        session = session.swipe(photo(1), SwipeDecision.KEEP, 1f, 1f)
        assertFalse("还剩一张就不算看完", session.finished)

        session = session.swipe(photo(2), SwipeDecision.KEEP, 1f, 1f)
        assertTrue(session.finished)
        assertNull(session.current)
    }

    /** 空队列是「这个范围里没有照片」，不是「都看完了」—— 两者文案与后续动作都不同。 */
    @Test
    fun `空队列不算看完`() {
        val session = SwipeSession(items = emptyList())

        assertFalse(session.finished)
        assertFalse(SwipeSession(items = emptyList(), index = 5).finished)
    }

    /**
     * 记录的是**调用方传进来的那张**，不是 `items[index]`：相册中途被系统改动时，
     * 索引指向的位置未必还是用户刚看到的那张。
     */
    @Test
    fun `历史记录的是实际划掉的那张`() {
        val shown = photo(999)

        val session = SwipeSession(items = queue(3))
            .swipe(shown, SwipeDecision.DELETE, -1f, 0f)

        assertEquals(shown, session.pendingDeletes.single())
        assertEquals(1, session.seen)
    }

    /**
     * 撤销栈裁剪的**红线**：宁可超一点内存，也绝不裁掉一条 DELETE。
     *
     * 这里把那条 DELETE 放在最前面，于是裁剪永远在第一格就停手，整条历史一条没少 ——
     * 这就是「删掉的条目再也裁不动」的证据。
     */
    @Test
    fun `裁剪到一条删除就停手`() {
        val items = queue(SwipeSession.MAX_HISTORY + 5, sizeBytes = 2_000L)

        var session = SwipeSession(items = items)
            .swipe(items[0], SwipeDecision.DELETE, -400f, 90f)
        for (i in 1 until items.size) {
            session = session.swipe(items[i], SwipeDecision.KEEP, 100f, 0f)
        }

        assertEquals("删除卡在最前面，裁剪一步都走不动", items.size, session.history.size)
        assertEquals(listOf(items[0]), session.pendingDeletes)
        assertEquals(items.size, session.seen)
    }

    /**
     * 裁剪真的会发生、而且只吃最前面的非删除条目；游标**不跟着退**
     * （这正是 `undo()` 里「游标与历史各退各的」要表达的那种情形）。
     */
    @Test
    fun `裁剪只吃最前面的非删除条目`() {
        val items = queue(SwipeSession.MAX_HISTORY + 40, sizeBytes = 2_000L)

        var session = SwipeSession(items = items)
        repeat(SwipeSession.MAX_HISTORY + 10) { i ->
            session = session.swipe(items[i], SwipeDecision.KEEP, 100f, 0f)
        }
        assertEquals(SwipeSession.MAX_HISTORY, session.history.size)
        assertEquals("游标不受裁剪影响", SwipeSession.MAX_HISTORY + 10, session.seen)

        val doomed = items[SwipeSession.MAX_HISTORY + 10]
        session = session.swipe(doomed, SwipeDecision.DELETE, -400f, 90f)
        var swipes = SwipeSession.MAX_HISTORY + 11
        repeat(20) {
            session = session.swipe(items[swipes], SwipeDecision.KEEP, 100f, 0f)
            swipes++
        }

        assertEquals("长度稳定在裁剪线上，不会一路涨上去", SwipeSession.MAX_HISTORY, session.history.size)
        assertEquals("那条删除被裁了 20 轮仍在", listOf(doomed), session.pendingDeletes)
        assertEquals(swipes, session.seen)
    }

    /**
     * 重开 App 恢复来的待删：不在撤销栈里，撤销也带不走它 ——
     * 它能被撤销才奇怪，撤销栈里根本没有它对应的那一步。
     */
    @Test
    fun `恢复的待删独立于撤销栈`() {
        val carried = photo(99, sizeBytes = 4_000L)

        var session = SwipeSession(items = queue(3), detachedDeletes = listOf(carried))
        assertEquals(listOf(carried), session.pendingDeletes)
        assertEquals(4_000L, session.pendingDeleteBytes)

        session = session.swipe(photo(1), SwipeDecision.KEEP, 1f, 1f)
        assertEquals("划一张保留不影响它", listOf(carried), session.pendingDeletes)

        session = session.undo()!!
        assertEquals("撤销那张保留也不影响它", listOf(carried), session.pendingDeletes)
        assertEquals(4_000L, session.pendingDeleteBytes)
        assertEquals(0, session.seen)
    }
}
