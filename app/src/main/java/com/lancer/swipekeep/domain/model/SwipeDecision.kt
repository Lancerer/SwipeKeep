package com.lancer.swipekeep.domain.model

/**
 * 一次滑动给出的判断。三个值对应三种手势方向。
 *
 * 「还没划过」这个状态**故意不存在** —— 它由队列里的位置表达，不需要枚举值去表示。
 */
enum class SwipeDecision {
    /** 右滑：保留 */
    KEEP,

    /** 左滑：删除 */
    DELETE,

    /** 上滑：暂不确定，留到以后再决定 */
    UNDECIDED,
    ;

    /**
     * 抛飞方向的横向分量：`-1` 往左、`+1` 往右、`0` 不横移。
     *
     * 用 `0` 而不是给待定一个 ±1：它抛的是**纵向**，常量乘出来的横向出场距离必须是 0，
     * 否则待定会一边上升一边斜着飞出屏幕。纵向那部分由 `SwipeCardStackView` 按高度算。
     */
    val horizontalExitSign: Int
        get() = when (this) {
            DELETE -> -1
            KEEP -> 1
            UNDECIDED -> 0
        }

    /** 抛飞是纵向的（待定）。横向两种走抛物线，纵向那种直上直下。 */
    val isVertical: Boolean get() = this == UNDECIDED

    companion object {
        /**
         * 横向位移 → 判断。左滑删除，右滑保留。
         *
         * 上滑**不从这里出来** —— 它有自己的闸门（`dy` 要显著大于 `dx`），
         * 在 `SwipeCardStackView.onViewReleased` 里先于这里判定。
         */
        fun forHorizontal(offsetX: Float): SwipeDecision =
            if (offsetX < 0f) DELETE else KEEP
    }
}
