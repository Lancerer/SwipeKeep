package com.lancer.swipekeep.ui.common

import android.content.Context
import android.text.format.Formatter
import com.lancer.swipekeep.R
import com.lancer.swipekeep.domain.model.PhotoItem
import com.lancer.swipekeep.domain.model.SortOrder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 文案格式化。集中在 `ui/common` 是因为同一组格式要在范围页、结算页、回收站页保持一致 ——
 * 分散在各 Fragment 里迟早会出现「范围页写 8.2 GB、结算页写 8.20 GB」这种不一致。
 */
object Formatters {

    /**
     * 用系统的 `Formatter` 而不是自己算 KB/MB/GB：它会按语言环境切十进制/二进制前缀
     * （中文环境下 GB 与 GiB 的显示习惯与英文不同），自己实现要重新踩一遍这个坑。
     */
    fun fileSize(context: Context, bytes: Long): String =
        Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0L))

    fun percent(fraction: Float): String = "${(fraction * 100f).toInt()}%"

    /** 「2024 年 4 月」/「未知日期」 */
    fun monthLabel(context: Context, year: Int, month: Int, isUnknown: Boolean): String =
        if (isUnknown) context.getString(R.string.scope_month_unknown) else context.getString(R.string.scope_month_format, year, month)

    fun sortLabel(context: Context, order: SortOrder): String = context.getString(
        if (order == SortOrder.NEWEST_FIRST) R.string.settings_sort_newest else R.string.settings_sort_oldest,
    )

    /**
     * 卡片信息条上的拍摄日期，「2024/05/12」。
     *
     * `DateTimeFormatter` 是**不可变且线程安全**的，所以可以共用一个实例当常量；
     * `SimpleDateFormat` 不能（它内部有可变状态），那种写法在多线程下会静默出错。
     */
    fun date(context: Context, millis: Long): String =
        if (millis <= PhotoItem.UNKNOWN_TIME) {
            context.getString(R.string.scope_month_unknown)
        } else {
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(DATE)
        }

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")
}
