package com.lancer.swipekeep.ui.common

import android.app.Activity
import android.widget.ScrollView
import androidx.recyclerview.widget.RecyclerView

/**
 * 调试用：启动时按 intent extra 把长页面滚到指定位置。
 *
 * 为什么需要这个：本机测试设备（小米 10 / MIUI）屏蔽了所有从命令行滚动/缩放屏幕的手段 ——
 *   - `adb shell input swipe` → SecurityException: 需要 INJECT_EVENTS
 *   - `adb shell wm density` → SecurityException: 需要 WRITE_SECURE_SETTINGS
 * 于是长页面没法用 adb 从命令行分段截图。退路就是让页面自己在启动时滚过去：
 *
 *   adb shell am start -n com.lancer.swipekeep/.MainActivity --ei scroll 900
 *   adb shell am start -n com.lancer.swipekeep/.MainActivity --ei scrollPos 12
 *
 * 注意：默认 launchMode 下 `am start` 会新建一个 Activity 实例，
 * 所以 onCreate 一定拿得到这个 extra，不需要处理 onNewIntent。
 *
 * 只用于开发期，不参与用户流程。
 */

/** 像素偏移。**单位是像素不是 dp** —— 小米 10 密度 440，900px 只有约 205dp。 */
fun ScrollView.applyDebugScrollFromIntent(activity: Activity?) {
    val offset = activity?.intent?.getIntExtra(EXTRA_SCROLL_Y, 0) ?: 0
    if (offset <= 0) return
    post { scrollTo(0, offset) }
}

/**
 * 列表按**条目位置**滚，而不是像素。
 *
 * 单位换掉是有意的：RecyclerView 的条目高度由内容决定，用像素偏移会随内容变化而
 * 指向不同的位置，脚本就没法稳定复现「滚到第 12 个月」这件事。
 */
fun RecyclerView.applyDebugScrollFromIntent(activity: Activity?) {
    val position = activity?.intent?.getIntExtra(EXTRA_SCROLL_POSITION, 0) ?: 0
    if (position <= 0) return
    post { scrollToPosition(position) }
}

private const val EXTRA_SCROLL_Y = "scroll"
private const val EXTRA_SCROLL_POSITION = "scrollPos"
