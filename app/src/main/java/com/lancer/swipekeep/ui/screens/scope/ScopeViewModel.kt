package com.lancer.swipekeep.ui.screens.scope

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lancer.swipekeep.SwipeKeepApp
import com.lancer.swipekeep.data.media.StorageStats
import com.lancer.swipekeep.data.media.readStorageStats
import com.lancer.swipekeep.data.prefs.PendingDeleteStore
import com.lancer.swipekeep.data.repository.PhotoRepository
import com.lancer.swipekeep.domain.PhotoBuckets
import com.lancer.swipekeep.domain.SavedSession
import com.lancer.swipekeep.domain.model.MonthBucket
import com.lancer.swipekeep.domain.model.SortOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 范围页的状态。
 *
 * 月份分桶、排序、合计都在 [PhotoBuckets] 里算 —— 那是纯 Kotlin、有单测的部分。
 * 这个 ViewModel 只负责把领域结果摊平成 UI 能直接渲染的行。
 */
class ScopeViewModel(
    private val repository: PhotoRepository,
    private val store: PendingDeleteStore,
    private val appContext: Context,
) : ViewModel() {

    private val sortOrder = MutableStateFlow(SortOrder.NEWEST_FIRST)

    /**
     * 上次没做完的清理。这一页只用到它的「范围 + 待删张数」——
     * 对账（把身份换成相册里的记录）是滑卡页的事，那一步需要一次扫描，
     * 而范围页没必要为了显示一个数字去扫整本相册。
     */
    private val savedSession = store.session

    val uiState: StateFlow<ScopeUiState> = combine(
        repository.photos(),
        sortOrder,
        savedSession,
    ) { photos, order, saved ->
        ScopeUiState(
            loading = false,
            storage = appContext.readStorageStats(),
            albumBytes = photos.sumOf { it.sizeBytes },
            totalCount = photos.size,
            sortOrder = order,
            savedSession = saved,
            rows = buildRows(
                buckets = PhotoBuckets.bucketByMonth(photos),
                totalCount = photos.size,
                sortOrder = order,
            ),
        )
    }.stateIn(
        scope = viewModelScope,
        // 5000ms：转屏或短暂切后台不重扫；真离开页面才停。
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ScopeUiState(),
    )

    fun toggleSortOrder() {
        sortOrder.value = sortOrder.value.toggled()
    }

    /**
     * 「全部撤销」：把待删记录抹掉。
     *
     * **磁盘上没有任何东西需要回滚** —— 待删只是一份「用户标记过」的记录，照片本身
     * 一张都没动过（这正是这个 App 的安全设计）。所以这里不需要二次确认。
     */
    fun discardSavedSession() {
        viewModelScope.launch { store.clear() }
    }

    /**
     * 行列表 = 4 个固定范围行 + N 个月份行。
     *
     * 固定行里只有「全部照片」真能点；另外三个是 V2 功能，`enabled = false`，
     * 由适配器渲染成置灰 + 「敬请期待」。把可点性放在数据模型里而不是 XML 里，
     * 是为了让它只有一个来源 —— 将来实现其中一个，只改这里。
     */
    private fun buildRows(
        buckets: List<MonthBucket>,
        totalCount: Int,
        sortOrder: SortOrder,
    ): List<ScopeRow> = buildList {
        add(ScopeRow.Action(ScopeRow.ActionKind.ALL_PHOTOS, count = totalCount, enabled = true, sortOrder = sortOrder))
        add(ScopeRow.Action(ScopeRow.ActionKind.SCREENSHOTS, count = null, enabled = false, sortOrder = sortOrder))
        add(ScopeRow.Action(ScopeRow.ActionKind.SIMILAR, count = null, enabled = false, sortOrder = sortOrder))
        add(ScopeRow.Action(ScopeRow.ActionKind.VIDEOS, count = null, enabled = false, sortOrder = sortOrder))
        buckets.forEach { add(ScopeRow.Month(it)) }
    }

    companion object {
        fun factory(app: SwipeKeepApp): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ScopeViewModel(
                    repository = app.container.photoRepository,
                    store = app.container.pendingDeleteStore,
                    // 只用 Application 级 context 读 StatFs，不会泄漏 Activity。
                    appContext = app,
                )
            }
        }
    }
}

/** 范围页的全部 UI 状态。`loading` 为 true 时其余字段无意义。 */
data class ScopeUiState(
    val loading: Boolean = true,
    val storage: StorageStats = StorageStats(0L, 0L),
    val albumBytes: Long = 0L,
    val totalCount: Int = 0,
    val sortOrder: SortOrder = SortOrder.NEWEST_FIRST,
    /** 上次没做完的清理（重开 App 后从磁盘读回）。没有就是 null，横幅不显示。 */
    val savedSession: SavedSession? = null,
    val rows: List<ScopeRow> = emptyList(),
)

/**
 * 一行。刻意**不持已格式化的字符串** —— 格式化要用 `Context.getString`，
 * 而 ViewModel 不该为了这个持有 Context。适配器拿到模型再拼文案。
 */
sealed interface ScopeRow {

    enum class ActionKind { ALL_PHOTOS, SCREENSHOTS, SIMILAR, VIDEOS }

    /** DiffUtil 的稳定标识。月份必须带上 `isUnknown`，否则「未知日期」桶会和 0 年 0 月撞车。 */
    val key: String

    data class Action(
        val kind: ActionKind,
        val count: Int?,
        val enabled: Boolean,
        val sortOrder: SortOrder,
    ) : ScopeRow {
        override val key: String get() = "action:$kind"
    }

    data class Month(val bucket: MonthBucket) : ScopeRow {
        override val key: String
            get() = if (bucket.isUnknown) "month:unknown" else "month:${bucket.year}-${bucket.month}"
    }
}
