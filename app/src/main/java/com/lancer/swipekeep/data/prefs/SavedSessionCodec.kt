package com.lancer.swipekeep.data.prefs

import com.lancer.swipekeep.domain.SavedSession
import com.lancer.swipekeep.domain.model.CleanupScope
import com.lancer.swipekeep.domain.model.PhotoItem
import com.lancer.swipekeep.domain.model.SortOrder

/**
 * [SavedSession] 与一个字符串之间的互转。
 *
 * ## 为什么不用 `org.json`（实施计划 §4.4 的原始选择）
 *
 * 计划书选 `org.json` 的理由是「Android 内置、零依赖、约 30 行、可单测」。前两条对，
 * 第三条不成立：`org.json` 在 JVM 单测里拿到的是 `android.jar` 的**桩**，任何方法都抛
 * `RuntimeException: Stub!`。要用它就得引 `org.json:json` 测试依赖或上 Robolectric，
 * 而这两样都与「domain/ 能在纯 JVM 上跑」的约束相抵触。
 *
 * 换成自定义格式之后，这条约束反而把设计推对了方向：**载荷里没有需要转义的东西**。
 * 照片的身份只有 volume 名（`external_primary` / `1a2b-3c4d` 这类）和一个数字 id，
 * 显示名、路径这些真正需要转义的字段压根不存（见 [SavedSession] 的类注释）。
 * 所以 `|` / `,` / `:` 当分隔符是安全的，编解码是纯 Kotlin，能在 JVM 上钉死。
 *
 * ## 失败一律返回 null
 *
 * 解不出来就当「没有存过会话」：清空一条待删队列比让 App 起不来好得多，
 * 而且这条路径现实中只有一种来源 —— 上一个版本的格式与现在不同。
 * 真要出问题，`log` 也帮不上忙：用户已经看不到那条队列了。
 */
object SavedSessionCodec {

    /** 记录分隔符。格式变了就换版本号，旧记录会被当成解不出来而丢弃。 */
    private const val VERSION = "v1"
    private const val RECORD = '|'
    private const val ITEM = ','
    private const val INNER = ':'

    private const val SCOPE_ALL = "A"
    private const val SCOPE_MONTH = "M"

    fun encode(session: SavedSession): String = buildString {
        append(VERSION).append(RECORD)
        when (val scope = session.scope) {
            is CleanupScope.AllPhotos -> {
                append(SCOPE_ALL).append(RECORD).append(scope.order.name)
            }

            is CleanupScope.ByMonth -> {
                append(SCOPE_MONTH).append(RECORD)
                append(scope.year).append(INNER)
                append(scope.month).append(INNER)
                append(if (scope.isUnknown) 1 else 0)
            }
        }
        append(RECORD).append(session.index).append(RECORD)
        session.pending.forEachIndexed { i, identity ->
            if (i > 0) append(ITEM)
            append(identity.volumeName).append(INNER).append(identity.id)
        }
    }

    fun decode(raw: String): SavedSession? {
        val parts = raw.split(RECORD)
        if (parts.size != FIELD_COUNT) return null
        if (parts[0] != VERSION) return null

        val scope = decodeScope(parts[1], parts[2]) ?: return null
        val index = parts[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val pending = decodePending(parts[4]) ?: return null

        return SavedSession(scope, index, pending)
    }

    private fun decodeScope(kind: String, params: String): CleanupScope? {
        when (kind) {
            SCOPE_ALL -> return SortOrder.entries
                .firstOrNull { it.name == params }
                ?.let { CleanupScope.AllPhotos(it) }

            SCOPE_MONTH -> {
                val fields = params.split(INNER)
                if (fields.size != 3) return null
                val year = fields[0].toIntOrNull() ?: return null
                val month = fields[1].toIntOrNull() ?: return null
                val unknown = when (fields[2]) {
                    "1" -> true
                    "0" -> false
                    else -> return null
                }
                return CleanupScope.ByMonth(year, month, unknown)
            }

            else -> return null
        }
    }

    /**
     * 空串是合法的（一条待删都没有）。**任何一项坏掉就是整条丢**，不做部分接受 ——
     * 半个队列比没有队列更危险：用户会以为「就剩这几张要删了」。
     */
    private fun decodePending(field: String): List<PhotoItem.Identity>? {
        if (field.isEmpty()) return emptyList()
        return field.split(ITEM).map { entry ->
            val split = entry.split(INNER)
            if (split.size != 2) return null
            val volume = split[0]
            if (volume.isEmpty()) return null
            val id = split[1].toLongOrNull() ?: return null
            PhotoItem.Identity(volume, id)
        }
    }

    private const val FIELD_COUNT = 5
}
