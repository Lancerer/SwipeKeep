package com.lancer.swipekeep.data.repository

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.lancer.swipekeep.data.media.MediaStoreDataSource
import com.lancer.swipekeep.domain.model.PhotoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 相册数据的唯一入口。范围页、滑卡页、结算页、回收站页都读这一条 Flow。
 *
 * 新鲜度靠 `ContentObserver`：在**系统相册里**删一张照片、或用别的 App 存一张新图，
 * 范围页的计数要自己跟着变 —— 这是阶段 2 的验收项之一。
 */
class PhotoRepository(
    private val context: Context,
    private val dataSource: MediaStoreDataSource,
    scope: CoroutineScope,
) {

    /**
     * 变化计数器。
     *
     * **必须是 `StateFlow`，不能用 `MutableSharedFlow(replay = 1)`。**
     * `replay` 重放的是「**已经发射过的**最后一个值」—— 如果从来没发射过，新订阅者什么也收不到。
     * 第一版就是踩了这个：`MutableSharedFlow(replay = 1)` 且只在 observer 回调里 `tryEmit`，
     * 于是首次进入范围页时 Flow 一直不发射，界面永远停在 `loading` 的初始状态
     * （表现是列表全空、存储卡显示「存储信息暂不可用」，而权限和查询其实都正常）。
     * `StateFlow` 一定持有一个当前值，新订阅者立刻就拿到，天然提供「首次收集即加载」。
     *
     * 用计数器而不是 `Unit`：`StateFlow` 会丢弃与当前值相等的发射，`Unit → Unit` 会被
     * 全部吞掉，那 observer 就白注册了。
     *
     * 同时 `StateFlow` 自带合并语义：批量删除逐条回调时，正在做 I/O 的收集者只会看到最新的
     * 那一次，中间值被跳过 —— 这正是要的「N 次通知合并成一次重扫」，所以不需要再套 `debounce`。
     */
    private val changes = MutableStateFlow(0L)

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            // 回调在主线程，单线程自增没有竞争。用自增而不是随机数是为了让「变了几次」可读。
            changes.value += 1L
        }
    }

    init {
        val registered = mutableListOf<Uri>()
        // observer 必须在有 Looper 的线程上注册。回调本身跑在主线程，我们只往里塞个信号，很快。
        for (collection in dataSource.observableCollections()) {
            runCatching {
                context.contentResolver.registerContentObserver(collection, true, observer)
                registered += collection
            }
        }
        // scope 与进程同生命周期（AppContainer.applicationScope），所以正常路径上这里永远不会触发。
        // 留着是为了让「用更短的 scope 创建 Repository」这种误用不会静默泄漏 observer。
        scope.coroutineContext[Job]?.invokeOnCompletion {
            // unregister 传的是 observer 本身，与注册在哪个 collection 上无关，所以逐个调用即可。
            repeat(registered.size) {
                runCatching { context.contentResolver.unregisterContentObserver(observer) }
            }
        }
    }

    /**
     * 冷流：每被收集一次就立刻扫一遍，之后每次相册变化再重扫。
     *
     * 冷流 + 各页面自行收集是有意的 —— 滑卡页与结算页要的是同一份真相，
     * 但不需要一个已经不在前台的页面还持有整份列表。
     */
    fun photos(): Flow<List<PhotoItem>> = changes.map { dataSource.loadPhotos() }
}
