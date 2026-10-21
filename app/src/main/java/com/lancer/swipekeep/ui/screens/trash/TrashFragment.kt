package com.lancer.swipekeep.ui.screens.trash

import android.app.Activity
import android.app.PendingIntent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lancer.swipekeep.R
import com.lancer.swipekeep.SwipeKeepApp
import com.lancer.swipekeep.databinding.FragmentTrashBinding
import com.lancer.swipekeep.domain.model.PhotoItem
import com.lancer.swipekeep.ui.common.applySystemBarInsets
import kotlinx.coroutines.launch

/**
 * 系统回收站：列出被移入回收站的照片，支持恢复与彻底删除。
 *
 * 两个动作都**由系统确认框**收尾（`createTrashRequest(..., false)` / `createDeleteRequest`），
 * 所以这里只负责把 `PendingIntent` 交出去。这一点很重要：App 自己没有删除权限，
 * 每一次真正的改动都得用户在那个系统框里再点一次 —— 恢复也一样（开放项 #1 尚未实测
 * 恢复方向会不会弹框，但两种结果这条路径都吃得下）。
 *
 * 「彻底删除」在系统框之前还有一层**自己**的确认：那是唯一真正释放空间的调用，
 * 而系统框的默认按钮在快捷操作里很容易被连点带过。
 */
class TrashFragment : Fragment() {

    private var _binding: FragmentTrashBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TrashViewModel by viewModels {
        TrashViewModel.factory(requireActivity().application as SwipeKeepApp)
    }

    private val container get() = (requireActivity().application as SwipeKeepApp).container

    /** 正在等系统框的那次操作。只用来决定结果 Toast 的文案；转屏后丢了也不影响正确性。 */
    private var pendingOp: Op? = null

    private enum class Op { RESTORE, DELETE }

    private lateinit var adapter: TrashAdapter

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val ok = result.resultCode == Activity.RESULT_OK
        val op = pendingOp
        pendingOp = null
        val outcome = when {
            !ok -> TrashViewModel.UiState.Result.CANCELLED
            op == Op.RESTORE -> TrashViewModel.UiState.Result.RESTORED
            op == Op.DELETE -> TrashViewModel.UiState.Result.DELETED
            else -> null
        }
        // 无论成功与否都重查：系统可能已经改了我们没预料到的东西（比如过期自动清除）。
        viewModel.refresh(outcome)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTrashBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.topBar.applySystemBarInsets(top = true)
        binding.root.applySystemBarInsets(bottom = true)

        adapter = TrashAdapter(
            imageLoader = container.imageLoader,
            uriOf = container.mediaStoreDataSource::uriFor,
            onRestore = ::restore,
            onDeleteForever = ::confirmDeleteForever,
        )
        binding.trashList.layoutManager = LinearLayoutManager(requireContext())
        binding.trashList.adapter = adapter
        binding.trashList.itemAnimator = null

        binding.backButton.setOnClickListener { findNavController().navigateUp() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: TrashViewModel.UiState) {
        adapter.submitList(state.photos)
        binding.trashCount.text = getString(R.string.trash_count, state.photos.size)
        binding.emptyText.isVisible = state.empty
        // 列表为空时把提示条也收起来：空页面上再说「7 天后自动清除」没有意义。
        binding.trashTip.isVisible = !state.empty

        when (state.lastResult) {
            TrashViewModel.UiState.Result.RESTORED -> toast(R.string.trash_restored)
            TrashViewModel.UiState.Result.DELETED -> toast(R.string.trash_deleted)
            TrashViewModel.UiState.Result.CANCELLED -> toast(R.string.trash_action_cancelled)
            TrashViewModel.UiState.Result.FAILED -> toast(R.string.trash_action_failed)
            null -> return
        }
        viewModel.consumeResult()
    }

    private fun restore(item: PhotoItem) {
        launch(Op.RESTORE) {
            container.mediaStoreDataSource.createTrashRequest(
                listOf(container.mediaStoreDataSource.uriFor(item)),
                trashed = false,
            )
        }
    }

    private fun confirmDeleteForever(item: PhotoItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.trash_delete_confirm_title)
            .setMessage(getString(R.string.trash_delete_confirm_message, 1))
            .setPositiveButton(R.string.trash_delete_forever) { _, _ ->
                launch(Op.DELETE) {
                    container.mediaStoreDataSource.createDeleteRequest(
                        listOf(container.mediaStoreDataSource.uriFor(item)),
                    )
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /**
     * `PendingIntent` 的构造与启动都可能抛（URI 不属于这个 App、系统组件缺失）。
     * 抛了就报一句实话，**不要**假装成功 —— 这些操作一个字节都没动过是最好的结果，
     * 而用户以为删了其实没删是最坏的那种不一致。
     */
    private fun launch(op: Op, build: () -> PendingIntent) {
        runCatching {
            val request = IntentSenderRequest.Builder(build().intentSender).build()
            pendingOp = op
            launcher.launch(request)
        }.onFailure {
            pendingOp = null
            toast(R.string.trash_action_failed)
        }
    }

    private fun toast(res: Int) {
        Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.trashList.adapter = null
        _binding = null
    }
}
