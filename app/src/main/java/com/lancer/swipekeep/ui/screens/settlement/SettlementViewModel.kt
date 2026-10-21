package com.lancer.swipekeep.ui.screens.settlement

import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lancer.swipekeep.SwipeKeepApp
import com.lancer.swipekeep.data.media.MediaStoreDataSource
import com.lancer.swipekeep.data.media.TrashCommitter
import com.lancer.swipekeep.data.prefs.PendingDeleteStore
import com.lancer.swipekeep.domain.model.PhotoItem
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 结算页的提交逻辑：把「待删」分批交给 `MediaStore.createTrashRequest`。
 *
 * 这一页在阶段 5 是**没有 ViewModel 的纯渲染器**，为了让「确认删除」能跨系统对话框、
 * 跨进程死亡活着，现在必须有一个。但它仍然只管提交，不管范围与统计数字 ——
 * 那几个数还是随导航参数进来（见 nav_graph.xml 里那段注释）。
 *
 * ## 为什么系统对话框必须由 Fragment 启动
 *
 * `createTrashRequest` 返回的是 `PendingIntent`，要由 **Activity** 的
 * `StartIntentSenderForResult` 启动。ViewModel 没有 Activity，所以这里只负责
 * 「下一批要发什么」+「结果回来之后状态怎么走」，真正的 launch 通过
 * [effects] 里的一条一次性事件交给 Fragment。
 *
 * ## 为什么进度存在 `SavedStateHandle`
 *
 * 系统对话框期间 Activity 可能被重建，ViewModel 却带着整份队列；进程也可能被回收。
 * 整份队列不必存（它就在 [PendingDeleteStore] 里），只需要存三个 Int：
 * 已提交张数、块大小、总张数（总张数可以从 store 重新算）。
 */
class SettlementViewModel(
    private val store: PendingDeleteStore,
    private val dataSource: MediaStoreDataSource,
    private val handle: SavedStateHandle,
) : ViewModel() {

    /** 界面要显示的全部提交状态。 */
    data class CommitUiState(
        val loading: Boolean = true,
        val total: Int = 0,
        val committed: Int = 0,
        val remaining: Int = 0,
        val batchCount: Int = 0,
        val phase: TrashCommitter.State = TrashCommitter.State.Idle,
        /** 本轮已经把待删全部提交完（跨进程死亡也要记得，见 [KEY_DONE]）。 */
        val completed: Boolean = false,
    ) {
        /** 全部提交完了。这一页要把「预计释放」换成「已移入回收站」。 */
        val done: Boolean get() = completed || (phase is TrashCommitter.State.Done && total > 0)

        /** 现在能不能点「确认删除」。 */
        val canStart: Boolean
            get() = !loading && total > 0 &&
                phase !is TrashCommitter.State.Awaiting &&
                phase !is TrashCommitter.State.Done

        /** 已经开过一轮（哪怕只提交了一批）。它决定「离开这一页该回哪儿」，见 Fragment。 */
        val commitStarted: Boolean
            get() = completed ||
                phase is TrashCommitter.State.Awaiting ||
                phase is TrashCommitter.State.Partial ||
                phase is TrashCommitter.State.Done
    }

    /**
     * 一次性事件。用 Channel 而不是 StateFlow：`Launch` 重复投递会让系统再弹一次确认框，
     * 而同一个请求被订阅两次（转屏、回前台）是**一定会发生**的。
     */
    sealed interface Effect {
        /** 让 Fragment 把这批交给系统对话框。 */
        data class Launch(val request: IntentSenderRequest) : Effect

        /** 用户取消了其中一批，整批已停止。 */
        data class Stopped(val committed: Int, val remaining: Int) : Effect
    }

    private val _state = MutableStateFlow(CommitUiState())
    val state: StateFlow<CommitUiState> = _state.asStateFlow()

    private val effectChannel = Channel<Effect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    /** 要提交的身份列表。**顺序就是提交顺序**，`committed` 是它的前缀长度。 */
    private var pending: List<PhotoItem.Identity> = emptyList()

    private var committer = TrashCommitter(total = 0)

    /**
     * 这一轮是否已经全部提交完。
     *
     * 单独存一个标记而不是靠 `phase is Done && total > 0` 推出来：提交成功后 store 被清空，
     * 进程一死就重新算出 `total = 0`，那时「完成」这件事在状态机里已经无处可寻了。
     */
    private var completed = false

    init {
        viewModelScope.launch {
            pending = store.load()?.pending.orEmpty()
            completed = handle[KEY_DONE] ?: false
            committer = TrashCommitter.restored(
                total = pending.size,
                chunkSize = handle[KEY_CHUNK] ?: TrashCommitter.DEFAULT_CHUNK,
                committed = handle[KEY_COMMITTED] ?: 0,
            )
            emit()
        }
    }

    /**
     * 用户确认「将分 N 批处理」之后调。
     *
     * 从 [TrashCommitter.State.Partial] 进来就是「继续处理剩余的 N 张」，两者走同一条路径。
     */
    fun start() {
        if (committer.state is TrashCommitter.State.Awaiting) return
        if (committer.committed >= committer.total) return
        committer = committer.begin()
        persist()
        emit()
        launchNextChunk()
    }

    /**
     * 系统对话框回来了。[ok] = `RESULT_OK`（用户点了允许）。
     *
     * **取消的语义是「停止整批」**，不是「跳过这一批接着弹下一批」——
     * 用户已经叫停了，再弹一个确认框是最烦人的那种行为（实施计划 §4.4）。
     */
    fun onChunkResult(ok: Boolean) {
        if (committer.state !is TrashCommitter.State.Awaiting) return

        if (ok) {
            committer = committer.onChunkDone()
            persist()
            emit()
            when (committer.state) {
                is TrashCommitter.State.Awaiting -> launchNextChunk()
                is TrashCommitter.State.Done -> finish()
                else -> Unit
            }
        } else {
            committer = committer.onChunkCancelled()
            persist()
            emit()
            viewModelScope.launch {
                effectChannel.send(Effect.Stopped(committer.committed, committer.remaining))
            }
            dropCommitted()
        }
    }

    /**
     * 构造下一批的 `PendingIntent`；如果 provider 说「太多了」，块减半重试同一位置。
     *
     * 循环而不是递归：块从 1000 一路减到 1 是一条有限路径，但写成递归时
     * 「重试几次」这件事在栈里看不见，而它恰好是这里唯一需要设上限的地方。
     */
    private fun launchNextChunk() {
        viewModelScope.launch {
            while (true) {
                val awaiting = committer.state as? TrashCommitter.State.Awaiting ?: return@launch
                val built = runCatching {
                    val end = (awaiting.from + awaiting.count).coerceAtMost(pending.size)
                    val uris = pending.subList(awaiting.from, end).map(dataSource::uriFor)
                    require(uris.isNotEmpty()) { "empty chunk" }
                    IntentSenderRequest.Builder(
                        dataSource.createTrashRequest(uris, trashed = true).intentSender,
                    ).build()
                }

                built.onSuccess { request ->
                    emit()
                    effectChannel.send(Effect.Launch(request))
                    return@launch
                }

                committer = committer.onChunkTooLarge()
                persist()
                emit()
                if (committer.state !is TrashCommitter.State.Awaiting) {
                    viewModelScope.launch {
                        effectChannel.send(Effect.Stopped(committer.committed, committer.remaining))
                    }
                    dropCommitted()
                    return@launch
                }
            }
        }
    }

    /** 全部提交成功：磁盘上那份待删记录可以抹掉了。 */
    private fun finish() {
        completed = true
        handle[KEY_DONE] = true
        emit()
        viewModelScope.launch { store.clear() }
    }

    /**
     * 只提交了一部分：把 store 里剩下的那截写回去。
     *
     * 不写回去的后果很具体：用户回来时范围页的横幅写着「上次有 20 张待删除」，
     * 而其中 5 张已经在回收站里了 —— 再点「继续处理」会把它们提交第二遍。
     *
     * 只在**停下**（用户取消 / provider 拒绝）时调。[onChunkResult] 的成功分支不调：
     * 那时要么还有下一批要发（`committed` 已经进了 `SavedStateHandle`），要么直接走
     * [finish] 清空。进程死在对话框期间的话，`restored` 会按栈里那个 `committed` 接着来，
     * 最多重复提交一批 —— 而重复提交已经进回收站的照片是幂等的。
     */
    private fun dropCommitted() {
        viewModelScope.launch {
            val saved = store.load() ?: return@launch
            val remaining = pending.drop(committer.committed)
            if (remaining.size == saved.pending.size) return@launch
            if (remaining.isEmpty()) {
                store.clear()
            } else {
                store.stage(saved.copy(pending = remaining))
                store.flush()
            }
        }
    }

    private fun emit() {
        _state.value = CommitUiState(
            loading = false,
            total = committer.total,
            committed = committer.committed,
            remaining = committer.remaining,
            batchCount = committer.batchCount,
            phase = committer.state,
            completed = completed,
        )
    }

    private fun persist() {
        handle[KEY_COMMITTED] = committer.committed
        handle[KEY_CHUNK] = committer.chunkSize
    }

    companion object {
        private const val KEY_COMMITTED = "commit.committed"
        private const val KEY_CHUNK = "commit.chunkSize"
        private const val KEY_DONE = "commit.done"

        fun factory(app: SwipeKeepApp): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettlementViewModel(
                    store = app.container.pendingDeleteStore,
                    dataSource = app.container.mediaStoreDataSource,
                    handle = createSavedStateHandle(),
                )
            }
        }
    }
}
