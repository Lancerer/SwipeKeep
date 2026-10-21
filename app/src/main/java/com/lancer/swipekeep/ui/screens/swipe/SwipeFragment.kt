package com.lancer.swipekeep.ui.screens.swipe

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.lancer.swipekeep.R
import com.lancer.swipekeep.SwipeKeepApp
import com.lancer.swipekeep.domain.model.CleanupScope
import com.lancer.swipekeep.domain.model.PhotoItem
import com.lancer.swipekeep.domain.model.SwipeDecision
import com.lancer.swipekeep.databinding.FragmentSwipeBinding
import com.lancer.swipekeep.ui.common.Formatters
import com.lancer.swipekeep.ui.common.applySystemBarInsets
import com.lancer.swipekeep.ui.widget.SwipeCardStackView
import kotlinx.coroutines.launch

/**
 * 滑卡清理主界面（设计图 01/02/03）。
 *
 * 这一层只做五件事：把 nav args 翻译成范围、把队列与依赖交给 [SwipeCardStackView]、
 * 把状态渲染出来、把三个控制键接到同一条抛飞路径上、到中场报一次战果。
 * 手势、抛飞、回弹、印章全部在卡片堆里，这里一行动画都没有。
 */
class SwipeFragment : Fragment() {

    private var _binding: FragmentSwipeBinding? = null
    private val binding get() = _binding!!

    private val args: SwipeFragmentArgs by navArgs()

    private val viewModel: SwipeViewModel by viewModels {
        SwipeViewModel.factory(requireActivity().application as SwipeKeepApp, resolveScope())
    }

    private val container get() = (requireActivity().application as SwipeKeepApp).container

    /**
     * 卡片堆只绑一次。
     *
     * 相册变化（`ContentObserver`）会让队列 Flow 重新发射，但**进度不能被重置** ——
     * 用户滑到第 80 张时系统相册刚好多了张截图，不该把他打回第 0 张。
     * 队列变了只影响进度行上的总数。
     */
    private var stackBound = false

    /** 最近一次渲染的状态。控制键的可点性同时取决于它与卡片堆的忙碌状态。 */
    private var lastState = SwipeUiState()

    /**
     * 已经报过的中场激励次数（第 20、40、60… 张各一次）。
     *
     * 用计数而不是「上一张张数」：转屏后 Fragment 重建、这些字段归零，
     * 而 ViewModel 里的进度还在 —— 用 `milestoneShown` 这个布尔量在第一帧把基线补上，
     * 就不会在旋转时莫名其妙弹一次激励。
     */
    private var milestonesShown = 0
    private var milestoneBaselineSet = false

    /** 淡出中场激励。单独拎出来是因为要在 `onDestroyView` 里按同一个引用取消掉。 */
    private val hideMilestone = Runnable {
        _binding?.milestone?.animate()?.alpha(0f)?.setDuration(MILESTONE_FADE_MS)?.start()
    }

    private val stackListener = object : SwipeCardStackView.Listener {

        override fun onSwiped(
            item: PhotoItem,
            decision: SwipeDecision,
            exitX: Float,
            exitY: Float,
        ) {
            viewModel.onSwiped(item, decision, exitX, exitY)
        }

        override fun onBusyChanged(busy: Boolean) = renderControls()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSwipeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.topBar.applySystemBarInsets(top = true)
        // 控制键必须吃 navigationBars inset，否则被手势条压住。
        binding.controlsBar.applySystemBarInsets(bottom = true)

        binding.cardStack.listener = stackListener
        binding.buttonDelete.setOnClickListener {
            binding.cardStack.swipeProgrammatically(SwipeDecision.DELETE)
        }
        binding.buttonKeep.setOnClickListener {
            binding.cardStack.swipeProgrammatically(SwipeDecision.KEEP)
        }
        binding.buttonUndo.setOnClickListener { undoLast() }
        // 两个入口共用同一个方法、同一帧的 lastState，所以两边的数字天然一致。
        binding.statusSettle.setOnClickListener { navigateToSettlement() }
        binding.endButton.setOnClickListener { navigateToSettlement() }
        binding.statusBack.setOnClickListener { findNavController().navigateUp() }
        binding.trashButton.setOnClickListener {
            findNavController().navigate(SwipeFragmentDirections.actionSwipeToTrash())
        }

        binding.progressText.text = getString(
            R.string.swipe_progress_format,
            scopeLabel(),
            0,
            0,
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    private fun render(state: SwipeUiState) {
        lastState = state

        // 队列第一次到齐才绑定。startIndex 用已看张数，这样视图重建（转屏）后
        // 卡片堆会接着之前的进度，而不是从头再滑一遍。
        if (!stackBound && state.total > 0) {
            stackBound = true
            binding.cardStack.bind(
                queue = viewModel.currentQueue,
                startIndex = state.seen,
                deps = SwipeCardStackView.CardDeps(
                    uriOf = container.mediaStoreDataSource::uriFor,
                    imageLoader = container.imageLoader,
                    soundPlayer = container.soundPlayer,
                ),
            )
        }

        binding.progressText.text = getString(
            R.string.swipe_progress_format,
            scopeLabel(),
            state.seen,
            state.total,
        )
        // max 不小于 1：进度条 drawable 在 max = 0 时会除零。
        binding.progressBar.max = state.total.coerceAtLeast(1)
        binding.progressBar.progress = state.seen

        val showStatus = state.empty || state.finished
        binding.statusPanel.isVisible = showStatus
        binding.statusText.text = if (state.empty) {
            getString(R.string.swipe_empty)
        } else {
            getString(R.string.swipe_finished, state.seen)
        }
        // 只有「看完了」才有可结算的东西；空范围该做的是换个范围。
        binding.statusSettle.isVisible = state.finished
        // 提前结束的入口，与上面那个「查看结算」互斥（finished 时它消失）。
        // 另外三种情况也不给：还在查询（卡片堆没到位）、空范围、以及一张都没划过 ——
        // 一张没划就走，结算页三张卡全是 0，看它还不如直接返回。
        binding.endButton.isVisible =
            !state.loading && !state.empty && !state.finished && state.seen > 0
        // 用 INVISIBLE 而不是 GONE：提示行还占着高度，卡片区的可用空间才不会因为
        // 状态面板出现而变一次，卡片也就不会重新按新尺寸量一遍。
        binding.hintRow.visibility = if (showStatus) View.INVISIBLE else View.VISIBLE

        binding.trashBadge.isVisible = state.pendingDeleteCount > 0
        binding.trashBadge.text = state.pendingDeleteCount.toString()

        renderControls()
        renderMilestone(state)
    }

    /**
     * 每滑满 [MILESTONE_EVERY] 张报一次战果 —— 滑到几百张时用户需要一点「确实在推进」的反馈。
     *
     * 用「已标记删除」而不是「已释放」：这一步只是记在 `SwipeSession.history` 里，
     * 磁盘一个字节都没动（实施计划 §1 第 1 条）。
     */
    private fun renderMilestone(state: SwipeUiState) {
        val reached = state.seen / MILESTONE_EVERY
        // 第一帧只对齐基线，不补报。转屏、或者从后台回来都会走到这里。
        if (!milestoneBaselineSet) {
            milestoneBaselineSet = true
            milestonesShown = reached
            return
        }
        if (reached <= milestonesShown) return
        milestonesShown = reached
        if (state.pendingDeleteCount <= 0) return

        val view = binding.milestone
        view.animate().cancel()
        view.removeCallbacks(hideMilestone)
        view.text = getString(
            R.string.swipe_milestone_format,
            state.pendingDeleteCount,
            Formatters.fileSize(requireContext(), state.pendingDeleteBytes),
        )
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(MILESTONE_FADE_MS).start()
        view.postDelayed(hideMilestone, MILESTONE_HOLD_MS)
    }

    /**
     * 三个控制键的可点性。**必须同时受卡片堆的忙碌状态约束** ——
     * 否则 280ms 抛飞期间连点两下会把状态机打乱（实施计划 §4.1 的「输入锁」）。
     */
    private fun renderControls() {
        val busy = binding.cardStack.isBusy
        // loading 也要卡住：相册还没查完时 total 是 0，只按 empty/finished 判断会让三盏灯
        // 在队列到位前先亮一下，点下去却是空的。
        val ready = !lastState.loading && !lastState.empty && !lastState.finished
        val canSwipe = !busy && ready
        val canUndo = canSwipe && lastState.canUndo
        // 「结束」同样要卡住 busy：抛飞那 280ms 里那一张的 onSwiped 还没提交，
        // 此刻跳结算会静默少算一张。
        val canEnd = !busy && ready

        binding.buttonDelete.isEnabled = canSwipe
        binding.buttonKeep.isEnabled = canSwipe
        binding.buttonUndo.isEnabled = canUndo
        binding.endButton.isEnabled = canEnd
        setDimmed(binding.buttonDelete, !canSwipe)
        setDimmed(binding.buttonKeep, !canSwipe)
        setDimmed(binding.buttonUndo, !canUndo)
        setDimmed(binding.endButton, !canEnd)
    }

    /** 灰掉不可点的按钮。ImageView 没有状态列表，disable 不会自己变灰。 */
    private fun setDimmed(view: View, dimmed: Boolean) {
        view.alpha = if (dimmed) 0.4f else 1f
    }

    /**
     * 去结算页，把这轮的结果当参数带过去。
     *
     * 数字取自 [lastState] 而不是去问 ViewModel —— 它就是这一帧渲染出来的那个状态，
     * 用户点按钮时看到的也正是它。分开取两次会出现「页面上写 12 张、结算页写 13 张」。
     *
     * 「结束」按钮与「查看结算」共用这里：前者必然 [SwipeUiState.finished] 为 false、
     * 后者必然为 true，所以 `completed` 就是实时那一帧的值，不需要调用方再传。
     */
    private fun navigateToSettlement() {
        val stats = lastState.settlement
        findNavController().navigate(
            SwipeFragmentDirections.actionSwipeToSettlement(
                year = args.year,
                month = args.month,
                isUnknown = args.isUnknown,
                deletedCount = stats.deletedCount,
                deletedBytes = stats.deletedBytes,
                keptCount = stats.keptCount,
                totalSeen = stats.totalSeen,
                completed = lastState.finished,
            ),
        )
    }

    private fun undoLast() {
        // 先问卡片堆忙不忙：栈的游标与 ViewModel 的历史长度必须同步，
        // 在动画中途弹出历史会让两者错开一格。
        if (binding.cardStack.isBusy) return
        val entry = viewModel.undo() ?: return
        binding.cardStack.undo(entry.exitX, entry.exitY)
    }

    /**
     * `year < 0` 是导航图里约定的「全部照片」。这个映射只在这一处发生 ——
     * 查照片、拼进度行文案都走它，避免「-1 的含义」在代码里出现第二个副本。
     */
    private fun resolveScope(): CleanupScope = if (args.year < 0) {
        CleanupScope.AllPhotos(args.sortOrder)
    } else {
        CleanupScope.ByMonth(args.year, args.month, args.isUnknown)
    }

    private fun scopeLabel(): String = when (val scope = resolveScope()) {
        is CleanupScope.AllPhotos -> getString(R.string.swipe_scope_all)
        is CleanupScope.ByMonth -> Formatters.monthLabel(
            requireContext(),
            scope.year,
            scope.month,
            scope.isUnknown,
        )
    }

    /**
     * 离开页面就把待删落盘。
     *
     * 放 `onStop` 而不是 `onDestroyView`：转屏也会走 `onDestroyView`，而转屏什么都没丢，
     * 没必要为它写一次磁盘。这里是「正常退出」的唯一保证点 —— 返回、去结算页、切后台
     * 都会经过它，所以只有被系统强杀才会丢最后那几张（见 `PendingDeleteStore`）。
     */
    override fun onStop() {
        super.onStop()
        viewModel.flushNow()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 绑定状态跟视图走，不跟 Fragment 走。少了这一句，从结算页「接着滑」回来时
        // （那条路径只弹掉结算页、本页的 view 重建）stackBound 还是 true，
        // render 里的 bind 不再执行，卡片堆就是一片空白。
        stackBound = false
        // 卡片堆持有 listener，listener 持有本 Fragment；把这条引用在视图销毁时剪断。
        binding.cardStack.listener = null
        // 激励的淡出是延迟投递的，视图没了还留着它就会去碰一个已销毁的 binding。
        binding.milestone.removeCallbacks(hideMilestone)
        _binding = null
    }

    private companion object {
        /** 滑满多少张报一次战果。 */
        const val MILESTONE_EVERY = 20

        const val MILESTONE_FADE_MS = 180L

        /** 停留时长：够读完一句话，又不至于挡住下一张卡。 */
        const val MILESTONE_HOLD_MS = 1_800L
    }
}
