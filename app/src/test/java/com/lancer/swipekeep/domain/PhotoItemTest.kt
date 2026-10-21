package com.lancer.swipekeep.domain

import com.lancer.swipekeep.domain.model.PhotoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 时间戳回退是整个 App 最容易出静默错误的地方：日期错了不会崩，只会让照片
 * 归错月份、或者整批掉进「未知日期」。所以这一处必须有单测。
 *
 * 全是纯 JVM 测试 —— `PhotoItem.resolveTakenAt` 是 Long 进 Long 出的纯函数，
 * 不需要 Robolectric，也不需要 Cursor。
 */
class PhotoItemTest {

    @Test
    fun `DATE_TAKEN 是毫秒，原样使用`() {
        assertEquals(1_700_000_000_000L, PhotoItem.resolveTakenAt(1_700_000_000_000L, null, null))
    }

    @Test
    fun `DATE_TAKEN 为空时回退到 DATE_ADDED，且要乘 1000`() {
        // 真实世界最常见的 case：微信保存的图、下载的图都没有 EXIF 拍摄时间。
        assertEquals(1_700_000_000_000L, PhotoItem.resolveTakenAt(null, 1_700_000_000L, null))
    }

    @Test
    fun `两个时间列都为空时回退到 DATE_MODIFIED`() {
        assertEquals(1_700_000_000_000L, PhotoItem.resolveTakenAt(null, null, 1_700_000_000L))
    }

    @Test
    fun `DATE_TAKEN 为 0 视同没有值，继续往下回退`() {
        // MediaStore 里确实存在「列有值但值是 0」的行；只用 != null 判断会得到 1970 年。
        assertEquals(1_700_000_000_000L, PhotoItem.resolveTakenAt(0L, 1_700_000_000L, null))
    }

    @Test
    fun `三个时间列都没有时得到 UNKNOWN_TIME`() {
        assertEquals(PhotoItem.UNKNOWN_TIME, PhotoItem.resolveTakenAt(null, null, null))
    }

    @Test
    fun `全为 0 也要落到 UNKNOWN_TIME，而不是 1970 年`() {
        assertEquals(PhotoItem.UNKNOWN_TIME, PhotoItem.resolveTakenAt(0L, 0L, 0L))
    }

    @Test
    fun `优先看 DATE_TAKEN，即使 DATE_ADDED 更大`() {
        // DATE_TAKEN 是拍摄时间、DATE_ADDED 是入库时间 —— 前者才是用户心里的「这张照片的时间」。
        assertEquals(1_600_000_000_000L, PhotoItem.resolveTakenAt(1_600_000_000_000L, 1_700_000_000L, 1_750_000_000L))
    }

    @Test
    fun `身份是 volumeName 加 id，不同 volume 的相同 id 不相等`() {
        // 这是「撤销栈 / 待删集合」能不能正确去重的前提。若身份退化成只有 id，
        // SD 卡与内置存储上 id 相同的两张照片会被当成同一张，删掉其中一张会连带丢掉另一张。
        val a = photo(id = 42L, volume = "external_primary")
        val b = photo(id = 42L, volume = "0123-4567")
        assertNotEquals(a, b)
        assertEquals(2, setOf(a, b).size)
    }

    private fun photo(id: Long, volume: String) = PhotoItem(
        id = id,
        volumeName = volume,
        displayName = "x.jpg",
        sizeBytes = 0L,
        takenAt = 0L,
        mimeType = "image/jpeg",
        width = 0,
        height = 0,
    )
}
