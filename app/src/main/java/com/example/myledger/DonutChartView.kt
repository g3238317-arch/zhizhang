package com.example.myledger

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 环形图。自己用 Canvas 画的，一个图表库都没装。
 *
 * 原理特别朴素：drawArc 按角度画弧，
 * 每块占多少角度 = 它的金额 ÷ 总金额 × 360°。
 * 所谓"画图表"，很多时候就是这个。
 */
class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Slice(val value: Float, val color: Int)

    private var slices: List<Slice> = emptyList()

    /** 0→1 的进度，用来做"转一圈长出来"的动画 */
    private var progress = 1f

    private var animator: ValueAnimator? = null

    /** 没数据时画的那个灰圈的颜色，由外部传进来跟主题对齐 */
    var emptyRingColor: Int = Color.parseColor("#F0EDE8")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val arcRect = RectF()

    fun setSlices(list: List<Slice>, animate: Boolean = true) {
        slices = list
        animator?.cancel()

        if (!animate) {
            progress = 1f
            invalidate()
            return
        }

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 550
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 先把圆环的几何算好，有没有数据都要用
        val diameter = minOf(width, height).toFloat()
        val stroke = diameter * 0.16f
        paint.strokeWidth = stroke

        val radius = (diameter - stroke) / 2f
        val cx = width / 2f
        val cy = height / 2f
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        val total = slices.map { it.value }.sum()
        if (total <= 0f) {
            // 没数据就画一个完整的灰圈，总比一片空白强（空白看着像坏了）
            paint.color = emptyRingColor
            canvas.drawArc(arcRect, 0f, 360f, false, paint)
            return
        }

        // 每块之间留一条小缝，不然相邻的色块会糊在一起
        val gap = 2f
        var startAngle = -90f   // 从 12 点方向起步，符合直觉

        for (slice in slices) {
            val fullSweep = slice.value / total * 360f
            paint.color = slice.color
            canvas.drawArc(
                arcRect,
                startAngle + gap / 2f,
                (fullSweep * progress - gap).coerceAtLeast(0.5f),
                false,
                paint
            )
            startAngle += fullSweep * progress
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}