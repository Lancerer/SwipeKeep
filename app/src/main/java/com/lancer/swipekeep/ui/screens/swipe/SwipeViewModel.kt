package com.lancer.swipekeep.ui.screens.swipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lancer.swipekeep.SwipeKeepApp
import com.lancer.swipekeep.data.prefs.PendingDeleteStore
import com.lancer.swipekeep.data.repository.PhotoRepository
import com.lancer.swipekeep.domain.SavedSession
import com.lancer.swipekeep.domain.Stats
import com.lancer.swipekeep.domain.SettlementStats
import com.lancer.swipekeep.domain.SwipeSession
import com.lancer.swipekeep.domain.model.CleanupScope
import com.lancer.swipekeep.domain.model.PhotoItem
import com.lancer.swipekeep.domain.model.SwipeDecision
import com.lancer.swipekeep.domain.restoreSession
import com.lancer.swipekeep.domain.toSavedSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 滑卡页：把仓库里的照片队列喂给 [SwipeSession]，再把它的状态摊平成一屏文案要的数字。
 *
 * 这一层现在**不含任何业务规则** —— 游标怎么走、历史怎么退、待删集合怎么算、
 * 会话怎么恢复，全在 `domain/` 里，那边能在纯 JVM 上单测。这里只负责：
 * 取一次队列快照、把 UI 事件转发过去、把状态映射成 [SwipeUiState]、把待删排队等落盘。
 */
class SwipeViewModel(
    private val repository: PhotoRepository,
    private val store: PendingDeleteStore,
    private val scope: CleanupScope,
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val session = MutableStateFlow(SwipeSession())

    /**
     * 相册查完了没有。
     *
     * 有它才能把「还在查」和「查完了，这个范围里确实一张都没有」分开 —— 少了它，
     * 进页面的一瞬间会先闪一下「这个范围里没有照片」，然后卡片才冒出来。
     */
    private val loaded = MutableStateFlow(false)

    /** 距离上次落盘又划了几张。到 [FLUSH_EVERY] 就写一次。 */
    private var swipesSinceFlush = 0

    init {
        viewModelScope.launch {
            // **一次扫描同时喂两个用途**：本次范围的队列，以及给待删做对账用的整本相册。
            // 对账必须拿整本相册 —— 待删可能来自另一个范围（先清了 5 月、再点全部照片），
            // 只拿本次范围去查会把它们当成「已经不存在」而全部丢掉。
            val album = repository.photos().first()
            val saved = store.load()
            val restored = restoreSession(scope, album, saved)

            // **只取这一份快照**，之后相册再变也不换。卡片堆 `bind` 的是这一份，
            // 「共 M 张」也只能是这一份：让分母跟着相册走的话，中途多进来一张截图就会
            // 让「已看 N / 共 M」与手上那摞卡对不上，更糟的是全部滑完之后 M 又涨了 ——
            // `finished` 变回 false，三盏灯全灭，而卡片堆已经空了，用户卡在一个什么都没有
            // 的界面上。想看到新照片，退出去再进来。
            session.value = SwipeSession(
                items = restored.queue,
                index = restored.startIndex,
                detachedDeletes = restored.pendingDeletes,
            )
            loaded.value = true

            // 恢复出来的会话立刻回写一次：换了范围的时候，上一次那批待删要跟着新范围落盘，
            // 否则在用户划出第一张之前被杀，它们还挂在旧范围名下（读得回来但游标对不上）。
            stage(flush = true)
        }
    }

    val uiState: StateFlow<SwipeUiState> = combine(session, loaded) { s, ready ->
        SwipeUiState(
            loading = !ready,
            total = s.items.size,
            seen = s.seen,
            settlement = Stats.computeSettlement(s),
            canUndo = s.history.isNotEmpty(),
            finished = s.finished,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SwipeUiState(),
    )

    /** 卡片堆第一次拿到队列时才需要它。与 [SwipeUiState] 里的 `total` 是同一份列表。 */
    val currentQueue: List<PhotoItem> get() = session.value.items

    fun onSwiped(item: PhotoItem, decision: SwipeDecision, exitX: Float, exitY: Float) {
        // 上滑待定也是「划过一张」，同样进历史 —— 撤销要能把它退回来。
        session.update { it.swipe(item, decision, exitX, exitY) }
        stage()
    }

    /**
     * 退一步并返回被撤销的那条记录 —— 调用方拿它的 `exitX/exitY` 把卡片从原处弹回来。
     * 没有可撤销的返回 null（撤销键在空栈时本来就是灰的）。
     */
    fun undo(): SwipeSession.Decision? {
        val current = session.value
        val entry = current.lastDecision ?: return null
        session.value = current.undo() ?: return null
        // 撤销掉一张删除会让待删少一张，磁盘上的那份也得跟着改。
        stage()
        return entry
    }

    /**
     * 立刻把待删写下去。离开页面时调 —— 正常退出（返回、去结算页、切后台）一条都不会丢，
     * 只有被系统强杀才会丢最后那几张（见 [PendingDeleteStore] 的类注释）。
     */
    fun flushNow() {
        appScope.launch { store.flush() }
    }

    override fun onCleared() {
        super.onCleared()
        flushNow()
    }

    /**
     * 把当前会话交给存储。[flush] 为 true 时不等 [FLUSH_EVERY]，立刻写。
     *
     * 落盘走 [appScope] 而不是 `viewModelScope`：离开页面时 `viewModelScope` 可能已经被取消，
     * 而那次落盘恰恰是最要紧的一次。
     */
    private fun stage(flush: Boolean = false) {
        store.stage(session.value.toSavedSession(scope))
        swipesSinceFlush++
        if (!flush && swipesSinceFlush < FLUSH_EVERY) return
        swipesSinceFlush = 0
        flushNow()
    }

    companion object {
        /**
         * 每划几张落一次盘。
         *
         * 5 张是按「被系统强杀时最多丢多少」定的：整批 2000 张的提交里，丢 4 张和丢 0 张
         * 对用户没有区别，但每张都写一次磁盘会掉帧。真正的兜底是「离开页面刷一次」。
         */
        private const val FLUSH_EVERY = 5

        fun factory(app: SwipeKeepApp, scope: CleanupScope): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SwipeViewModel(
                    repository = app.container.photoRepository,
                    store = app.container.pendingDeleteStore,
                    scope = scope,
                    appScope = app.container.applicationScope,
                )
            }
        }
    }
}

/**
 * 滑卡页的 UI 状态。
 *
 * 刻意**不含已格式化的文案** —— 格式化要 `Context.getString`，而 ViewModel 不该为此持有 Context。
 * 范围标题由 Fragment 自己拼（它本来就有 nav args）。
 */
data class SwipeUiState(
    val loading: Boolean = true,
    val total: Int = 0,
    val seen: Int = 0,
    /** 结算要用的几个数。中场激励要的「已标记删除 N 张 · 约 X MB」也从这里取。 */
    val settlement: SettlementStats = SettlementStats(),
    val canUndo: Boolean = false,
    val finished: Boolean = false,
) {
    val empty: Boolean get() = !loading && total == 0

    val pendingDeleteCount: Int get() = settlement.deletedCount

    val pendingDeleteBytes: Long get() = settlement.deletedBytes
}
