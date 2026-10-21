package com.lancer.swipekeep.ui.screens.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lancer.swipekeep.SwipeKeepApp
import com.lancer.swipekeep.data.media.MediaStoreDataSource
import com.lancer.swipekeep.domain.model.PhotoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 系统回收站的列表。
 *
 * **不缓存**：系统可能已经让某些项过了 7 天自动消失，别的 App 也可能恢复了它们。
 * 每次调用 [refresh]（进入页面、每次恢复/彻底删除之后）都是一次真实查询 ——
 * 实施计划 §4.4 明确要求「不要在多次导航之间缓存回收站列表」。
 */
class TrashViewModel(private val dataSource: MediaStoreDataSource) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val photos: List<PhotoItem> = emptyList(),
        /** 上一次操作的结果，给 Fragment 报一次 Toast 用。消费后置回 null。 */
        val lastResult: Result? = null,
    ) {
        val empty: Boolean get() = !loading && photos.isEmpty()

        enum class Result { RESTORED, DELETED, CANCELLED, FAILED }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh(result: UiState.Result? = null) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, lastResult = result)
            val photos = runCatching { dataSource.loadTrashedPhotos() }
                .getOrElse { emptyList() }
                // 回收站里按时间倒序：刚删的通常就是用户此刻在找的。
                .sortedByDescending { it.takenAt }
            _state.value = UiState(loading = false, photos = photos, lastResult = result)
        }
    }

    /** Toast 只该报一次。Fragment 显示完调用它。 */
    fun consumeResult() {
        if (_state.value.lastResult == null) return
        _state.value = _state.value.copy(lastResult = null)
    }

    companion object {
        fun factory(app: SwipeKeepApp): ViewModelProvider.Factory = viewModelFactory {
            initializer { TrashViewModel(app.container.mediaStoreDataSource) }
        }
    }
}
