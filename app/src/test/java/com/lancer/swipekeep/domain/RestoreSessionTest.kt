package com.lancer.swipekeep.domain

import com.lancer.swipekeep.domain.model.CleanupScope
import com.lancer.swipekeep.domain.model.PhotoItem
import com.lancer.swipekeep.domain.model.SwipeDecision
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话恢复。
 *
 * 这里的三处判断都只在「杀进程 → 重开 → 继续处理」这条路上生效，而它们错了的表现是
 * **用户看到一张自己已经决定要删的照片、或者一张照片被算两次**。手滑验证不了，
 * 所以用夹具钉住。
 */
class RestoreSessionTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun photo(id: Long, year: Int, month: Int, day: Int, bytes: Long = 1_000L) = PhotoItem(
        id = id,
        volumeName = "external_primary",
        displayName = "IMG_$id.jpg",
        sizeBytes = bytes,
        takenAt = LocalDate.of(year, month, day)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli(),
        mimeType = "image/jpeg",
        width = 100,
        height = 100,
    )

    private val september = CleanupScope.ByMonth(year = 2026, month = 9)

    /** 九月五张（1..5 号各一张，于是顺序确定），八月一张（id 90）。 */
    private val album = listOf(
        photo(1, 2026, 9, 1),
        photo(2, 2026, 9, 2),
        photo(3, 2026, 9, 3),
        photo(4, 2026, 9, 4),
        photo(5, 2026, 9, 5),
        photo(90, 2026, 8, 20),
    )

    @Test
    fun `没存过会话就是新的一轮`() {
        val restored = restoreSession(september, album, saved = null, zone = zone)

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), restored.queue.map { it.id })
        assertEquals(0, restored.startIndex)
        assertTrue(restored.pendingDeletes.isEmpty())
    }

    /**
     * 同一范围：游标是「剔除前的位置」，所以要按同样的条件重新数一遍。
     * 剔除掉第 2 张之后，原先看到第 3 张的位置落到了新队列的下标 2 上。
     */
    @Test
    fun `同一范围接着上次的位置，已删的不再出现`() {
        val saved = SavedSession(
            scope = september,
            index = 3,
            pending = listOf(PhotoItem.Identity("external_primary", 2L)),
        )

        val restored = restoreSession(september, album, saved, zone)

        assertEquals("第 2 张已标记删除，不在队列里", listOf(1L, 3L, 4L, 5L), restored.queue.map { it.id })
        assertEquals("下标 2 = 接着看第 4 张", 2, restored.startIndex)
        assertEquals(listOf(2L), restored.pendingDeletes.map { it.id })
    }

    /**
     * 换了范围：队列从第一张开始，但**上一次的待删要跟着过来**。
     * 不带过来的话，用户先清了 5 月、再清 6 月，5 月那批标记会静默消失。
     */
    @Test
    fun `换了范围从第一张开始，待删仍然带过来`() {
        val saved = SavedSession(
            scope = CleanupScope.ByMonth(year = 2026, month = 8),
            index = 1,
            pending = listOf(PhotoItem.Identity("external_primary", 90L)),
        )

        val restored = restoreSession(september, album, saved, zone)

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), restored.queue.map { it.id })
        assertEquals("上次的游标属于另一个范围，不能继承", 0, restored.startIndex)
        assertEquals(listOf(90L), restored.pendingDeletes.map { it.id })
    }

    /** 对账：待删里那条已经被别的 App 删掉了，就得从待删里去掉。 */
    @Test
    fun `待删里已经不在相册的那些被丢掉`() {
        val saved = SavedSession(
            scope = september,
            index = 2,
            pending = listOf(
                PhotoItem.Identity("external_primary", 99L),
                PhotoItem.Identity("external_primary", 2L),
                PhotoItem.Identity("gone-volume", 1L),
            ),
        )

        val restored = restoreSession(september, album, saved, zone)

        assertEquals("只剩还在相册里的那张", listOf(2L), restored.pendingDeletes.map { it.id })
        assertEquals(listOf(1L, 3L, 4L, 5L), restored.queue.map { it.id })
    }

    /** 存下来的游标超出了当前队列（相册被清过），不能越界。 */
    @Test
    fun `游标超出队列时钳到末尾`() {
        val saved = SavedSession(
            scope = september,
            index = 999,
            pending = emptyList(),
        )

        val restored = restoreSession(september, album, saved, zone)

        assertEquals(5, restored.startIndex)
    }

    /**
     * 落盘的那一份 = 撤销栈里派生的待删 + 恢复来的待删，两者**合起来**才算「现在总共要删哪些」。
     * 只写其中一边，用户重开 App 后都会少一批。
     */
    @Test
    fun `落盘的待删同时含本次划的与上次恢复的`() {
        val carried = album[5] // 八月那张，来自上一次会话
        val session = SwipeSession(
            items = album.take(5),
            detachedDeletes = listOf(carried),
        )
            .swipe(album[0], SwipeDecision.DELETE, -400f, 0f)
            .swipe(album[1], SwipeDecision.KEEP, 200f, 0f)
            .swipe(album[2], SwipeDecision.DELETE, -400f, 0f)

        val saved = session.toSavedSession(september)

        assertEquals(september, saved.scope)
        assertEquals(3, saved.index)
        assertEquals(3, saved.pendingCount)
        assertTrue(saved.pending.contains(carried.identity))
        assertTrue(saved.pending.contains(album[0].identity))
    }
}
