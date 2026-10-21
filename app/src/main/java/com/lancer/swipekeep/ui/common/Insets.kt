package com.lancer.swipekeep.ui.common

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 把系统栏 inset 叠加到 View **已有**的 padding 上。
 *
 * targetSdk 36 下 edge-to-edge 是强制的、退不出去，所以每个页面都得自己吃 inset。
 *
 * 关键点：必须记住**初始** padding 再叠加。写成 `view.paddingTop + bars.top` 会在
 * 每次 inset 派发时累加 —— 这是这类代码最经典的 bug，表现为第二次派发后顶部/底部多空一块。
 */
fun View.applySystemBarInsets(
    top: Boolean = true,
    bottom: Boolean = false,
    start: Boolean = false,
    end: Boolean = false,
) {
    val baseLeft = paddingLeft
    val baseTop = paddingTop
    val baseRight = paddingRight
    val baseBottom = paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(
            baseLeft + if (start) bars.left else 0,
            baseTop + if (top) bars.top else 0,
            baseRight + if (end) bars.right else 0,
            baseBottom + if (bottom) bars.bottom else 0,
        )
        insets
    }
}
