package com.lancer.swipekeep.ui.screens.settlement

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lancer.swipekeep.R
import com.lancer.swipekeep.SwipeKeepApp
import com.lancer.swipekeep.data.media.TrashCommitter
import com.lancer.swipekeep.databinding.FragmentSettlementBinding
import com.lancer.swipekeep.ui.common.Formatters
import com.lancer.swipekeep.ui.common.applySystemBarInsets
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 清理结算页（设计图 04）+ 阶段 6 的「确认删除」。
 *
 * 数字仍然全部走导航参数（见 nav_graph.xml 的注释），这一页**依旧不重新算统计**。
 * 阶段 6 加进来的 [SettlementViewModel] 只管一件事：把待删分批交给系统回收站。
 * 它能存在的前提是「提交有跨对话框、跨进程死亡的状态」，这跟「统计该由谁算」无关。
 */
class SettlementFragment : Fragment() {

    private var _binding: FragmentSettlementBinding? = null
    private val binding get() = _binding!!

    private val args: SettlementFragmentArgs by navArgs()

    private val viewModel: SettlementViewModel by viewModels {
        SettlementViewModel.factory(requireActivity().application as SwipeKeepApp)
    }

    /** 最近一次提交状态。返回键与主 CTA 的去向都取决于它，见 [leave] 与 [onViewCreated]。 */
    private var lastCommit = SettlementViewModel.CommitUiState()

    /**
     * 系统确认框的桥。
     *
     * **必须由 Fragment 启动**：ViewModel 没有 Activity，而 `StartIntentSenderForResult`
     * 需要一个 Activity 的 ActivityResultRegistry。ViewModel 只把「下一批发什么」投递过来。
     */
    private val trashLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        // 只把 OK 当成「用户允许」。其它一切（CANCELED、被系统抑制、provider 拒绝）
        // 都按「停下」处理：不知道结果时继续弹下一批，等于替用户做了决定。
        viewModel.onChunkResult(result.resultCode == Activity.RESULT_OK)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettlementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 加在内容容器上而不是根布局（ScrollView）上：inset 是 padding，加给 ScrollView 会让
        // 可滚动区域本身变小，而加在内容上就只是「内容不从系统栏下面开始」。
        binding.content.applySystemBarInsets(top = true, bottom = true)

        render()

        binding.ctaNext.setOnClickListener { leave() }
        binding.ctaTrash.setOnClickListener {
            findNavController().navigate(SettlementFragmentDirections.actionSettlementToTrash())
        }
        binding.ctaConfirm.setOnClickListener { confirmThenStart() }

        // 一旦开始提交，**离开这一页就不能回到滑卡页**：滑卡页的 ViewModel 还握着整份旧会话
        // （含刚刚提交掉的那些 DELETE），它下一次滑动的 `stage()` 会把已经进回收站的照片
        // 重新写回待删队列 —— 横幅又冒出来，用户再提交一遍。回范围页会销毁那个 ViewModel。
        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::renderCommit) }
                launch { viewModel.effects.collect(::handleEffect) }
            }
        }
    }

    // ---------------------------------------------------------------- 提交

    private fun handleEffect(effect: SettlementViewModel.Effect) {
        when (effect) {
            is SettlementViewModel.Effect.Launch ->
                runCatching { trashLauncher.launch(effect.request) }
                    .onFailure {
                        // 系统对话框都拉不起来（ROM 缺组件、IntentSender 失效）。
                        // 按「用户取消」处理：状态停在 Partial，剩余的都还在 store 里，不会丢。
                        Toast.makeText(
                            requireContext(),
                            R.string.settle_commit_unavailable,
                            Toast.LENGTH_LONG,
                        ).show()
                        viewModel.onChunkResult(ok = false)
                    }

            is SettlementViewModel.Effect.Stopped -> showStoppedDialog(effect)
        }
    }

    /**
     * 用户取消了其中一批。**必须把「已处理 N / 还剩 M」说清楚**：
     * 前几批是真删了的，静默回到结算页会让人以为什么都没发生。
     *
     * 对话框只是方便，不是唯一入口 —— 状态本身停在 Partial，重进这一页时
     * 「确认删除」按钮会变成「继续处理剩余的 M 张」。
     */
    private fun showStoppedDialog(effect: SettlementViewModel.Effect.Stopped) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settle_stopped_title)
            .setMessage(
                getString(R.string.settle_stopped_message, effect.committed, effect.remaining),
            )
            .setPositiveButton(getString(R.string.settle_confirm_continue, effect.remaining)) { _, _ ->
                viewModel.start()
            }
            .setNegativeButton(R.string.settle_confirm_later, null)
            .show()
    }

    private fun confirmThenStart() {
        val state = lastCommit
        if (!state.canStart) return

        // 多批时先自己说清楚「会弹 N 次确认框」：系统对话框一次只覆盖一批，
        // 用户连续看到第二个框时不该以为自己点重了。
        val message = if (state.batchCount > 1) {
            getString(R.string.settle_confirm_message_multi, state.total, state.batchCount)
        } else {
            getString(R.string.settle_confirm_message_single, state.total)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settle_confirm_title)
            .setMessage(message)
            .setPositiveButton(R.string.settle_confirm_ok) { _, _ -> viewModel.start() }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun renderCommit(state: SettlementViewModel.CommitUiState) {
        lastCommit = state

        val phase = state.phase
        val awaiting = phase is TrashCommitter.State.Awaiting
        val showConfirm = !state.done && state.total > 0 && !awaiting
        binding.ctaConfirm.isVisible = showConfirm
        binding.ctaConfirm.isEnabled = state.canStart
        binding.ctaConfirm.text = if (phase is TrashCommitter.State.Partial) {
            getString(R.string.settle_confirm_continue, state.remaining)
        } else {
            getString(R.string.settle_cta_confirm, state.total)
        }

        // 确认删除出现时它才是主 CTA，把设计图的「继续清理」降成次级 ——
        // 两个橙色实心按钮上下叠着会让人分不清哪个才是「下一步」。
        applyPrimaryStyle(binding.ctaNext, primary = !showConfirm)

        // 「预计释放」在提交成功之后就是假的词：字节仍然躺在系统回收站里，要等彻底删除
        // 或者系统过期才回来（实施计划 §1 第 1 条）。这里只改标签，不改数字。
        binding.statFreedLabel.setText(
            if (state.done) R.string.settle_stat_freed_done else R.string.settle_stat_freed_label,
        )

        // 回收站入口只在「真有东西进过回收站」时才报数字。还没提交时它也可能能点
        // （用户想看系统回收站里有什么），但不能挂着一个「（12）」—— 那 12 张还不在里面。
        if (args.deletedCount > 0) {
            binding.ctaTrash.isVisible = true
            val inTrash = if (state.committed > 0) state.committed else null
            binding.ctaTrash.text = if (inTrash != null) {
                getString(R.string.settle_cta_trash, inTrash)
            } else {
                getString(R.string.settle_cta_trash_plain)
            }
        }
    }

    private fun applyPrimaryStyle(view: android.widget.TextView, primary: Boolean) {
        view.setBackgroundResource(
            if (primary) R.drawable.bg_cta_primary else R.drawable.bg_cta_secondary,
        )
        view.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (primary) R.color.white else R.color.ink,
            ),
        )
        view.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            resources.getDimension(if (primary) R.dimen.cta_text else R.dimen.cta_secondary_text),
        )
    }

    /**
     * 离开这一页。
     *
     * 两条路的目的地不同，因为「下一步该去哪」本来就不同：
     * - 没开始提交：完成 → 范围页；提前结束 → 回滑卡页接着滑（那边 ViewModel 与游标还在）。
     * - 已经开始提交：一律回范围页（**销毁滑卡页的 ViewModel**，理由见 onViewCreated 的注释）。
     */
    private fun leave() {
        val destination = if (lastCommit.commitStarted) {
            R.id.scopeFragment
        } else if (args.completed) {
            R.id.scopeFragment
        } else {
            R.id.swipeFragment
        }
        findNavController().popBackStack(destination, false)
    }

    // ---------------------------------------------------------------- 渲染

    private fun render() {
        // 两种终态：滑完整个范围 vs 顶栏「结束」提前收工。文案必须分开 ——
        // 只看了 5 / 1000 张时说「清理完成」「相册清爽多了」，是这一页最容易撒的谎。
        val completed = args.completed

        binding.settleTitle.text = getString(
            if (completed) R.string.settle_title_format else R.string.settle_title_paused_format,
            scopeLabel(),
        )
        binding.settleSubtitle.text = getString(
            if (completed) {
                R.string.settle_subtitle_format
            } else {
                R.string.settle_subtitle_paused_format
            },
            args.totalSeen,
        )

        // 绿勾只属于「完成」。提前结束换成品牌橙：对勾本身保留（进度确实存好了），
        // 但不用「绿勾 = 已收工」这个强约定去暗示一件没做完的事。
        binding.settleCheck.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                requireContext(),
                if (completed) R.color.keep_green else R.color.brand_orange,
            ),
        )

        binding.statFreed.text = Formatters.fileSize(requireContext(), args.deletedBytes)
        binding.statDeleted.text = args.deletedCount.toString()
        binding.statKept.text = args.keptCount.toString()

        val decided = args.keptCount + args.deletedCount
        // 「去向」整块只在真做过决定时才出现：只上滑几张「待定」就收工的话 decided 是 0，
        // 百分比只能算出「删除 100%」、堆叠条全红，与上面的统计卡（0 / 0）直接矛盾。
        val hasDecision = decided > 0
        binding.whereaboutsLabel.isVisible = hasDecision
        binding.stackedBar.isVisible = hasDecision
        binding.legendRow.isVisible = hasDecision

        // 两个百分比必须加起来刚好 100：各自四舍五入会凑出 49% / 50% 这种一眼就不对的组合。
        // 所以只算保留那边，删除那边用 100 减出来。
        val keepPercent = if (decided <= 0) 0 else (args.keptCount * 100f / decided).roundToInt()
        binding.stackedBar.keepFraction = if (decided <= 0) 0f else args.keptCount.toFloat() / decided
        binding.legendKeep.text = getString(R.string.settle_legend_keep, keepPercent)
        binding.legendDelete.text = getString(R.string.settle_legend_delete, 100 - keepPercent)

        // 一张都没标记删除时不显示回收站入口：一个「查看回收站（0）」的按钮点进去是空的。
        binding.ctaTrash.isVisible = args.deletedCount > 0
        binding.ctaTrash.text = getString(R.string.settle_cta_trash, args.deletedCount)

        binding.ctaNext.text = getString(
            if (completed) R.string.settle_cta_next else R.string.settle_cta_paused,
        )
    }

    /**
     * 标题里的范围。用「9 月」而不是 `Formatters.monthLabel` 的「2026 年 9 月」——
     * 30sp 的标题一行放不下后者，而这一页是刚从那个范围里退出来的。
     */
    private fun scopeLabel(): String = when {
        args.year < 0 -> getString(R.string.swipe_scope_all)
        args.isUnknown -> getString(R.string.scope_month_unknown)
        else -> getString(R.string.settle_scope_month, args.month)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
