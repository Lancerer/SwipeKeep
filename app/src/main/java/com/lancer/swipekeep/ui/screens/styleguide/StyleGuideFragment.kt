package com.lancer.swipekeep.ui.screens.styleguide

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.lancer.swipekeep.databinding.FragmentStyleguideBinding
import com.lancer.swipekeep.ui.common.applyDebugScrollFromIntent
import com.lancer.swipekeep.ui.common.applySystemBarInsets

/**
 * 设计令牌与自定义 View 的验收台：把设计图里的组件逐个并排渲染，便于与
 * docs/产品图 下的 SVG 目视比对。
 *
 * 这是**开发期页面**，正常用户流程进不到这里（只在 nav_graph 里存在，没有入口指向它）。
 * 阶段 8 收尾时可整个删掉。
 */
class StyleGuideFragment : Fragment() {

    private var _binding: FragmentStyleguideBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentStyleguideBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.content.applySystemBarInsets(top = true)
        binding.root.applyDebugScrollFromIntent(requireActivity())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
