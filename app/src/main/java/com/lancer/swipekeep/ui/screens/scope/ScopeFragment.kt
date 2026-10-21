package com.lancer.swipekeep.ui.screens.scope

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.lancer.swipekeep.R
import com.lancer.swipekeep.SwipeKeepApp
import com.lancer.swipekeep.data.media.MediaPermissions
import com.lancer.swipekeep.databinding.FragmentScopeBinding
import com.lancer.swipekeep.domain.model.CleanupScope
import com.lancer.swipekeep.domain.model.SortOrder
import com.lancer.swipekeep.ui.common.Formatters
import com.lancer.swipekeep.ui.common.applyDebugScrollFromIntent
import com.lancer.swipekeep.ui.common.applySystemBarInsets
import kotlinx.coroutines.launch

/**
 * 选择清理范围（设计图 05）。
 *
 * 数据来自 [ScopeViewModel] → `PhotoRepository` → MediaStore。这一层只做三件事：
 * 把状态渲染出来、把点击转成导航、把 insets 处理好。
 */
class ScopeFragment : Fragment() {

    private var _binding: FragmentScopeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ScopeViewModel by viewModels {
        ScopeViewModel.factory(requireActivity().application as SwipeKeepApp)
    }

    private lateinit var adapter: ScopeRowsAdapter

    /** 只在本次视图存活期内自动请求一次，避免「拒绝 → onResume → 又弹」的死循环。 */
    private var autoRequested = false

    /** 缓存最近一次权限判定，供 [render] 决定空状态文案用。 */
    private var permissionGranted = true

    /**
     * 最近一次渲染的状态。留着是为了让 [applyPermissionState]（`onResume` 会被调）
     * 也能重画横幅 —— 否则从系统设置里授权回来时，横幅还停在上一帧的可见性上。
     */
    private var lastState = ScopeUiState()

    /**
     * 回调里的 map **不可信** —— MIUI 与 API 34+ 的部分授权都不如实上报。
     * 所以回调只当「可以重新检查了」的信号，真实状态一律问 `MediaPermissions`。
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        applyPermissionState()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentScopeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 顶栏贴状态栏、底栏让开手势条。两处都是「捕获 base padding 再加 inset」的实现。
        binding.topBar.applySystemBarInsets(top = true)
        binding.bottomBar.applySystemBarInsets(bottom = true)
        // 月份列表可能很长，而 MIUI 上没法用 adb 滚动（见 DebugScroll）。
        // 加在 RecyclerView 上而不是 root 上：这里可滚的是列表，不是整页。
        binding.rows.applyDebugScrollFromIntent(requireActivity())

        adapter = ScopeRowsAdapter(
            onStartAllPhotos = { order -> navigateToSwipe(sortOrder = order, year = ALL_PHOTOS) },
            onStartMonth = { row -> navigateToSwipe(sortOrder = SortOrder.NEWEST_FIRST, year = row.bucket.year, month = row.bucket.month, isUnknown = row.bucket.isUnknown) },
            onToggleSort = { viewModel.toggleSortOrder() },
        )
        binding.rows.layoutManager = LinearLayoutManager(requireContext())
        binding.rows.adapter = adapter
        // 行高固定、内容不会横向变化，关掉动画能让「计数变了」这类更新不产生位移抖动。
        binding.rows.itemAnimator = null

        binding.startButton.setOnClickListener { navigateToSwipe(sortOrder = currentSortOrder(), year = ALL_PHOTOS) }
        binding.settingsButton.setOnClickListener {
            // 阶段 8 接设置页。现在明确说出来，好过点了没反应。
            Toast.makeText(requireContext(), R.string.scope_settings_coming_soon, Toast.LENGTH_SHORT).show()
        }

        binding.permissionRetryButton.setOnClickListener {
            permissionLauncher.launch(MediaPermissions.required())
        }
        binding.permissionSettingsButton.setOnClickListener { openAppSettings() }
        binding.pendingResume.setOnClickListener { resumeSavedSession() }
        binding.pendingDiscard.setOnClickListener { viewModel.discardSavedSession() }

        // 先定好权限形态再开始渲染：否则首帧的 render 会在 permissionGranted 还是默认值
        // 的状态下跑一遍，把空状态闪出来。
        applyPermissionState()

        viewLifecycleOwner.lifecycleScope.launch {
            // repeatOnLifecycle(STARTED)：页面不可见时停止收集，避免后台持续持有整份照片列表。
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 必须每次回来都重查：用户可能是去系统设置里授权的，回来时 Fragment 并没有重建。
        // 这也是为什么 accessLevel 不缓存结果。
        applyPermissionState()
        if (!autoRequested && MediaPermissions.accessLevel(requireContext()) == MediaPermissions.AccessLevel.DENIED) {
            autoRequested = true
            permissionLauncher.launch(MediaPermissions.required())
        }
    }

    /**
     * 权限状态决定「显示范围列表还是显示索取权限的面板」。两者互斥 ——
     * 没有权限时列表必然为空，那时显示「相册里还没有照片」会把「没授权」误报成「相册是空的」。
     *
     * `emptyHint` 不在这里设置：它的可见性同时取决于权限和照片数，由 [render] 统一决定，
     * 两处都写会让两个条件互相覆盖。
     */
    private fun applyPermissionState() {
        permissionGranted = MediaPermissions.accessLevel(requireContext()) != MediaPermissions.AccessLevel.DENIED
        binding.permissionPanel.isVisible = !permissionGranted
        binding.storageCard.isVisible = permissionGranted
        binding.pickTitle.isVisible = permissionGranted
        binding.rows.isVisible = permissionGranted
        binding.bottomBar.isVisible = permissionGranted
        renderPendingBanner()
    }

    /**
     * 「上次有 N 张待删除」横幅。
     *
     * 没权限时不显示：「继续处理」会打开一个空空如也的滑卡页，用户会以为那批标记丢了 ——
     * 实际上它们好好地在磁盘上，只是现在读不到相册。
     */
    private fun renderPendingBanner() {
        val count = lastState.savedSession?.pendingCount ?: 0
        binding.pendingBanner.isVisible = permissionGranted && count > 0
        // 数字先摆好再决定显不显示：隐藏状态下留着上一次的文案，显出来的一瞬间不会先空一下。
        binding.pendingText.text = getString(R.string.scope_pending_banner, count)
    }

    /** 「继续处理」：按存下来的范围打开滑卡页。滑卡页会自己认出「这是同一个范围」并接着上次的位置。 */
    private fun resumeSavedSession() {
        when (val scope = lastState.savedSession?.scope ?: return) {
            is CleanupScope.AllPhotos -> navigateToSwipe(sortOrder = scope.order, year = ALL_PHOTOS)

            is CleanupScope.ByMonth -> navigateToSwipe(
                sortOrder = SortOrder.NEWEST_FIRST,
                year = scope.year,
                month = scope.month,
                isUnknown = scope.isUnknown,
            )
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", requireContext().packageName, null))
        // 个别 ROM 没有这个 Activity；不给退出路径比崩溃好。
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(requireContext(), R.string.permission_settings_unavailable, Toast.LENGTH_SHORT).show() }
    }

    private fun render(state: ScopeUiState) {
        lastState = state
        adapter.submitList(state.rows)
        // 三个条件缺一不可：loading 未结束时不报空；没权限时由权限面板负责解释。
        binding.emptyHint.isVisible = permissionGranted && !state.loading && state.totalCount == 0
        binding.startButton.isEnabled = !state.loading && state.totalCount > 0
        renderPendingBanner()

        if (state.storage.totalBytes <= 0L) {
            // StatFs 拿不到就明说拿不到，不显示一个编出来的百分比。
            binding.storageDonut.progress = 0f
            binding.storageDonut.centerText = null
            binding.storageUsed.text = getString(R.string.scope_storage_unavailable)
            binding.storageAdvice.text = null
            return
        }
        binding.storageDonut.progress = state.storage.fraction
        binding.storageDonut.centerText = Formatters.percent(state.storage.fraction)
        binding.storageUsed.text = getString(
            R.string.scope_storage_used,
            Formatters.fileSize(requireContext(), state.storage.usedBytes),
            Formatters.fileSize(requireContext(), state.storage.totalBytes),
        )
        // 这里刻意不用「预计可释放 8 GB」那种文案：那个数字没有任何依据。
        // 相册实际占多少是真实的，也是用户唯一能判断「值不值得清」的信息。
        binding.storageAdvice.text = getString(
            R.string.scope_storage_advice,
            Formatters.fileSize(requireContext(), state.albumBytes),
        )
    }

    private fun currentSortOrder(): SortOrder = viewModel.uiState.value.sortOrder

    private fun navigateToSwipe(
        sortOrder: SortOrder,
        year: Int,
        month: Int = -1,
        isUnknown: Boolean = false,
    ) {
        findNavController().navigate(
            ScopeFragmentDirections.actionScopeToSwipe(
                sortOrder = sortOrder,
                year = year,
                month = month,
                isUnknown = isUnknown,
            ),
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // RecyclerView 持有 adapter，adapter 持有 Fragment 的 lambda；不清会让整棵视图树
        // 跟着 Fragment 的 ViewModel 一起被多留一会儿。
        binding.rows.adapter = null
        _binding = null
    }

    private companion object {
        /** 导航图里约定的「全部照片」，见 nav_graph.xml 的注释 */
        const val ALL_PHOTOS = -1
    }
}
