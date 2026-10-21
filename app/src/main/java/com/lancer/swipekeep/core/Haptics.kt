package com.lancer.swipekeep.core

import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 震动反馈。走 `View.performHapticFeedback(int)` 而不是 `Vibrator`：
 *
 * - **不需要权限**（`VIBRATE` 一个字都不用声明）
 * - 自动尊重系统「触感反馈」开关，用户关掉了就不会震
 * - 被 view 自己的 `isHapticFeedbackEnabled` 把关，可以按 view 关掉
 *
 * 这些常量都是 API 30 起的，正好与本工程的 minSdk 一致。
 */
object Haptics {

    /** 右滑保留：系统语义里的「确认」 */
    fun confirm(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    /** 左滑删除：系统语义里的「拒绝」 */
    fun reject(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    }

    /** 撤销、点按钮这类轻反馈 */
    fun tick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}
