package com.hoopai.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class FeedbackOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val feedbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val trail = ArrayDeque<PointF>()
    private var symbol = ""
    private var alphaValue = 0f
    private var scale = 0.7f
    private var animator: ValueAnimator? = null

    fun setBallPoint(x: Float, y: Float) {
        trail.addLast(PointF(x, y))
        while (trail.size > 18) trail.removeFirst()
        invalidate()
    }

    fun clearTrail() {
        trail.clear()
        invalidate()
    }

    fun showSuccess() = show("✓", 0xFF39E58C)
    fun showMiss() = show("×", 0xFFFF4D5F)

    private fun show(text: String, color: Int) {
        animator?.cancel()
        symbol = text
        feedbackPaint.color = color
        alphaValue = 1f
        scale = 0.72f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 720
            addUpdateListener {
                val t = it.animatedFraction
                scale = 0.72f + 0.38f * minOf(t * 2.5f, 1f)
                alphaValue = if (t < 0.18f) t / 0.18f else max(0f, 1f - (t - 0.18f) / 0.82f)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    symbol = ""
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (trail.size > 1) {
            trailPaint.color = 0xFFFFB347.toInt()
            trailPaint.alpha = 210
            val path = Path()
            trail.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            canvas.drawPath(path, trailPaint)
            pointPaint.color = 0xFFFFB347.toInt()
            trail.forEach { p -> canvas.drawCircle(p.x, p.y, 3.5f, pointPaint) }
        }
        if (symbol.isNotEmpty()) {
            canvas.save()
            canvas.translate(width / 2f, height * 0.32f)
            canvas.scale(scale, scale)
            feedbackPaint.alpha = (255f * alphaValue).toInt()
            feedbackPaint.textSize = minOf(width, height) * 0.22f
            canvas.drawText(symbol, 0f, feedbackPaint.textSize * 0.35f, feedbackPaint)
            canvas.restore()
        }
    }
}