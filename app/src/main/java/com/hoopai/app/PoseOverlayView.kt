package com.hoopai.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

class PoseOverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 230, 118); strokeWidth = 6f; style = Paint.Style.STROKE
    }
    private val point = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private var pts: List<NormalizedLandmark> = emptyList()
    private val edges = arrayOf(
        11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16,
        11 to 23, 12 to 24, 23 to 24, 23 to 25, 25 to 27,
        24 to 26, 26 to 28, 27 to 29, 29 to 31, 28 to 30, 30 to 32
    )
    fun setLandmarks(v: List<NormalizedLandmark>) { pts = v; postInvalidate() }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (pts.isEmpty()) return
        for ((a,b) in edges) if (a < pts.size && b < pts.size) {
            val p=pts[a]; val q=pts[b]
            if (p.visibility().orElse(0f) > .35f && q.visibility().orElse(0f) > .35f)
                c.drawLine(p.x()*width,p.y()*height,q.x()*width,q.y()*height,line)
        }
        for (p in pts) if (p.visibility().orElse(0f) > .35f)
            c.drawCircle(p.x()*width,p.y()*height,5f,point)
    }
}