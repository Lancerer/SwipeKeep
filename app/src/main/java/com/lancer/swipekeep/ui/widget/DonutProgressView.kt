package com.lancer.swipekeep.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.lancer.swipekeep.R

/**
 * 存储占用圆环（设计图 05：半径 28dp、线宽 9dp、起点 rotate -90°）。
 *
 * 原设计图上那句「75%」画在圆环中心，所以这里也自己画，省掉外层再套一个 FrameLayout 对齐。
 */
class DonutProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 0..1 */
    var progress: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    var strokeWidth: Float

    var trackColor: Int

    var progressColor: Int

    var capRound: Boolean

    var centerText: String? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var centerTextSize: Float

    var centerTextColor: Int

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val arcBounds = RectF()

    init {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.DonutProgressView)
        try {
            progress = ta.getFloat(R.styleable.DonutProgressView_donutProgress, 0f)
            strokeWidth = ta.getDimension(
                R.styleable.DonutProgressView_donutStroke,
                resources.getDimension(R.dimen.donut_stroke),
            )
            trackColor = ta.getColor(
                R.styleable.DonutProgressView_donutTrackColor,
                context.getColor(R.color.track),
            )
            progressColor = ta.getColor(
                R.styleable.DonutProgressView_donutProgressColor,
                context.getColor(R.color.brand_orange),
            )
            capRound = ta.getBoolean(R.styleable.DonutProgressView_donutCapRound, true)
            centerText = ta.getString(R.styleable.DonutProgressView_donutCenterText)
            centerTextSize = ta.getDimension(
                R.styleable.DonutProgressView_donutCenterTextSize,
                resources.getDimension(R.dimen.donut_center_text),
            )
            centerTextColor = ta.getColor(
                R.styleable.DonutProgressView_donutCenterTextColor,
                context.getColor(R.color.ink),
            )
        } finally {
            ta.recycle()
        }
        ringPaint.strokeWidth = strokeWidth
        ringPaint.strokeCap = if (capRound) Paint.Cap.ROUND else Paint.Cap.BUTT
        textPaint.textSize = centerTextSize
        textPaint.color = centerTextColor
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (resources.getDimension(R.dimen.donut_size) + paddingLeft + paddingRight).toInt()
        setMeasuredDimension(
            resolveSize(desired, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 描边是居中绘制的，所以内缩半个线宽，避免被 View 边界裁掉
        val inset = strokeWidth / 2f
        arcBounds.set(
            paddingLeft + inset,
            paddingTop + inset,
            width - paddingRight - inset,
            height - paddingBottom - inset,
        )
        if (arcBounds.width() <= 0f || arcBounds.height() <= 0f) return

        ringPaint.color = trackColor
        canvas.drawArc(arcBounds, 0f, 360f, false, ringPaint)

        if (progress > 0f) {
            ringPaint.color = progressColor
            canvas.drawArc(arcBounds, START_ANGLE, 360f * progress, false, ringPaint)
        }

        centerText?.takeIf { it.isNotEmpty() }?.let { text ->
            // 让文字视觉居中：用基线偏移大约半个字高
            val baseline = arcBounds.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(text, arcBounds.centerX(), baseline, textPaint)
        }
    }

    private companion object {
        /** 设计图里的 rotate(-90)，即从 12 点方向起画 */
        const val START_ANGLE = -90f
    }
}
