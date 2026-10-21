package com.lancer.swipekeep.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.lancer.swipekeep.R

/**
 * 结算页的「本月去向」堆叠条（设计图 04：红色的整条 + 左侧绿色覆盖，两端都圆角）。
 *
 * 绿色在最上层从左侧开始铺 [keepFraction]，所以剩余部分自然露出底色红。
 */
class StackedBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 绿（保留）部分占比，0..1 */
    var keepFraction: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    var keepColor: Int

    var deleteColor: Int

    var cornerRadius: Float

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()

    init {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.StackedBarView)
        try {
            keepFraction = ta.getFloat(R.styleable.StackedBarView_barKeepFraction, 0f)
            keepColor = ta.getColor(
                R.styleable.StackedBarView_barKeepColor,
                context.getColor(R.color.keep_green),
            )
            deleteColor = ta.getColor(
                R.styleable.StackedBarView_barDeleteColor,
                context.getColor(R.color.delete_red),
            )
            cornerRadius = ta.getDimension(
                R.styleable.StackedBarView_barCorner,
                resources.getDimension(R.dimen.stacked_bar_h) / 2f,
            )
        } finally {
            ta.recycle()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bounds.set(
            paddingLeft.toFloat(),
            paddingTop.toFloat(),
            (width - paddingRight).toFloat(),
            (height - paddingBottom).toFloat(),
        )
        val radius = cornerRadius.coerceAtMost(bounds.height() / 2f)
        if (bounds.width() <= 0f || bounds.height() <= 0f) return

        paint.color = deleteColor
        canvas.drawRoundRect(bounds, radius, radius, paint)

        val keepWidth = bounds.width() * keepFraction
        if (keepWidth > 0f) {
            paint.color = keepColor
            // 至少画到能容纳一个圆角，否则窄比例下会退化成小尖角
            val w = keepWidth.coerceAtLeast(radius * 2f).coerceAtMost(bounds.width())
            canvas.drawRoundRect(
                bounds.left,
                bounds.top,
                bounds.left + w,
                bounds.bottom,
                radius,
                radius,
                paint,
            )
        }
    }
}
