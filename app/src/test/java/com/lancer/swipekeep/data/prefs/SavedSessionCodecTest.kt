package com.lancer.swipekeep.data.prefs

import com.lancer.swipekeep.domain.SavedSession
import com.lancer.swipekeep.domain.model.CleanupScope
import com.lancer.swipekeep.domain.model.PhotoItem
import com.lancer.swipekeep.domain.model.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待删会话的持久化格式。
 *
 * 这里要钉住的是一件不会立刻显形的事：**写下去的东西必须能原样读回来**。
 * 它只在「用户杀进程后重开 App」时才走到，而那条路径失败了不报错 —— 用户只会看到
 * 「上次那 200 张标记白做了」。所以往返相等、以及各种坏输入不抛异常，都写在测试里。
 */
class SavedSessionCodecTest {

    private val identity = PhotoItem.Identity("external_primary", 42L)

    @Test
    fun `月份范围的往返`() {
        val session = SavedSession(
            scope = CleanupScope.ByMonth(year = 2026, month = 9),
            index = 137,
            pending = listOf(
                identity,
                PhotoItem.Identity("external_primary", 7L),
                PhotoItem.Identity("1a2b-3c4d", 9L),
            ),
        )

        assertEquals(session, SavedSessionCodec.decode(SavedSessionCodec.encode(session)))
    }

    @Test
    fun `全部照片范围的往返，待删为空`() {
        val session = SavedSession(
            scope = CleanupScope.AllPhotos(SortOrder.OLDEST_FIRST),
            index = 0,
            pending = emptyList(),
        )

        val encoded = SavedSessionCodec.encode(session)
        assertEquals(session, SavedSessionCodec.decode(encoded))
        // 空待删也要占住字段：靠 split 的段数校验格式，少一段就是坏数据。
        assertTrue("空队列也要能编出完整的五段", encoded.split('|').size == 5)
    }

    @Test
    fun `未知日期范围的往返`() {
        val session = SavedSession(
            scope = CleanupScope.ByMonth(year = 0, month = 0, isUnknown = true),
            index = 3,
            pending = listOf(identity),
        )

        assertEquals(session, SavedSessionCodec.decode(SavedSessionCodec.encode(session)))
    }

    @Test
    fun `坏数据一律当成没存过，不抛异常`() {
        val bad = listOf(
            "",
            "v2|A|NEWEST_FIRST|1|",                    // 版本不认识
            "v1|A|NEWEST_FIRST|1",                     // 少一段
            "v1|X|whatever|1|",                        // 范围类型不认识
            "v1|A|SOMETIMES|1|",                       // 排序不认识
            "v1|M|2026:9|1|",                          // 月份段数不对
            "v1|M|2026:9:也许|1|",                      // 布尔不认识
            "v1|M|2026:9:0|-4|",                       // 负的游标
            "v1|M|2026:9:0|x|",                        // 游标不是数字
            "v1|M|2026:9:0|1|external_primary",        // 身份缺 id
            "v1|M|2026:9:0|1|:5",                      // 身份缺 volume
            "v1|M|2026:9:0|1|external_primary:abc",    // id 不是数字
            "v1|M|2026:9:0|1|external_primary:5,bad",  // 第二条坏掉 → 整条丢
        )

        for (raw in bad) {
            assertNull("应当拒绝：$raw", SavedSessionCodec.decode(raw))
        }
    }
}
