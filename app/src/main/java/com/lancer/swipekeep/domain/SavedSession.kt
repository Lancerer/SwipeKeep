package com.lancer.swipekeep.domain

import com.lancer.swipekeep.domain.model.CleanupScope
import com.lancer.swipekeep.domain.model.PhotoItem
import java.time.ZoneId

/**
 * 存到磁盘上的那次清理会话。
 *
 * ## 为什么只存「身份」不存整条 [PhotoItem]
 *
 * 三个理由，任何一个单独都够：
 * 1. **整条记录活不过一次对账。** 照片可能在这期间被别的 App 删了、或已经进了回收站，
 *    那样 `sizeBytes` / `displayName` 都是过期信息。身份 + 一份新的扫描才能得到真值。
 * 2. 载荷从「一条几十字节」降到「两个字段」，而 DataStore 每次 `edit{}` 都重写整个文件。
 * 3. 没有可转义的内容（volume 名形如 `external_primary` / `1a2b-3c4d`，id 是数字），
 *    所以编解码可以是不依赖 `org.json` 的纯 Kotlin，因而**能在 JVM 上单测**。
 *
 * ## 为什么存的是「待删」而不是整条撤销栈
 *
 * 撤销栈里 `KEEP` / `UNDECIDED` 那两类，重开 App 之后**没有意义**：撤销一张保留等于
 * 「把那张照片再显示一遍」，而照片本来就还在相册里、一步都没动过。留着它们只会让
 * 载荷随滑动张数无限增长。真正丢不起的只有待删集合 —— 它是阶段 6 要提交的输入。
 *
 * 代价说清楚：**重开 App 之后撤销栈是空的**，所以「撤销」按钮一开始是灰的，
 * 那些恢复来的待删只能整体「全部撤销」，不能逐张退。这是有意的取舍，不是漏做。
 */
data class SavedSession(
    /** 上次清理的范围。跟本次相同才谈得上「接着上次的进度」。 */
    val scope: CleanupScope,
    /** 上次看到第几张。用于 [restoreSession] 把用户放回原处。 */
    val index: Int,
    /** 已标记删除、还没提交的那些照片的身份。 */
    val pending: List<PhotoItem.Identity>,
) {
    val pendingCount: Int get() = pending.size
}

/**
 * 当前会话 → 要落盘的那一份。
 *
 * `pendingDeletes` 已经同时包含撤销栈里派生出来的与恢复来的（见 [SwipeSession.detachedDeletes]），
 * 所以这里**不需要区分它们从哪来** —— 落盘的是「现在总共要删哪些」，恢复时它们一律进
 * `detachedDeletes`，因为它们对应的撤销栈都随上一次进程一起没了。
 */
fun SwipeSession.toSavedSession(scope: CleanupScope): SavedSession = SavedSession(
    scope = scope,
    index = seen,
    pending = pendingDeletes.map { it.identity },
)

/**
 * 会话开始时把「存下来的东西」对上一次新扫描的结果。
 *
 * 对账是必须的：待删队列里可能有已经被别的 App 删掉、或用户自己去系统回收站里
 * 彻底删掉的照片。留着它们会让结算页报出一个提交不上去的张数。
 * **一次性做，不逐项查** —— 逐项查是 N 次 provider 往返。
 */
data class RestoredSession(
    /** 本次要滑的队列。已经标记删除的那些**不在里面**。 */
    val queue: List<PhotoItem>,
    /** 队列里的起点。换过范围就是 0。 */
    val startIndex: Int,
    /** 对账之后仍然存在的待删。它们不进撤销栈，见 [SavedSession] 的类注释。 */
    val pendingDeletes: List<PhotoItem>,
)

/**
 * 由「本次范围 + 一次新扫描 + 存下来的会话」算出这次会话的三个初始量。
 *
 * 抽成纯函数的理由和 [PhotoBuckets] 一样：这里有三处容易写错的地方 ——
 * 队列要剔除已删的、游标要跟着这个剔除一起换算、换了范围就不能继承上次的游标 ——
 * 而这三点都只能靠构造夹具来验证，手滑验证不了。
 *
 * [zone] 显式传参，单测里钉死时区，否则「元旦 00:30 拍的那张算哪个月」会随运行机器漂移。
 */
fun restoreSession(
    scope: CleanupScope,
    album: List<PhotoItem>,
    saved: SavedSession?,
    zone: ZoneId = ZoneId.systemDefault(),
): RestoredSession {
    val full = PhotoBuckets.filterFor(album, scope, zone)

    val pending = saved?.pending.orEmpty()
    val deletes = if (pending.isEmpty()) {
        emptyList()
    } else {
        val byIdentity = album.associateBy { it.identity }
        pending.mapNotNull { byIdentity[it] }
    }
    val excluded = deletes.mapTo(HashSet()) { it.identity }

    // 队列剔除已标记删除的。不剔的话，「先清了 5 月、再点全部照片」会把 5 月那批
    // 已经决定要删的照片再显示一遍 —— 用户只能再删一次，而那一张会被算两次，
    // 结算页的「删除张数」和「预计释放」都会偏大。
    val queue = full.filterNot { it.identity in excluded }

    // 存下来的 index 是**剔除之前**那个队列里的位置，所以要按同样的条件重新数一遍，
    // 不能直接拿来用。换了范围则无从换算，从第一张开始。
    val savedIndex = if (saved != null && saved.scope == scope) saved.index else 0
    val startIndex = full.take(savedIndex.coerceIn(0, full.size)).count { it.identity !in excluded }

    return RestoredSession(
        queue = queue,
        startIndex = startIndex.coerceIn(0, queue.size),
        pendingDeletes = deletes,
    )
}
