package com.hoopai.app

import android.content.Context
import android.graphics.*
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

class WorkoutOverlayView(context: Context) : View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var path:List<PointF> = emptyList()
    private var ball:RectF?=null
    private var hoop:RectF?=null
    private var pose:List<NormalizedLandmark> = emptyList()
    private var event:String?=null
    fun setState(p:List<PointF>, b:RectF?, ps:List<NormalizedLandmark>, h:RectF?, e:String?){
        path=p; ball=b; pose=ps; hoop=h; event=e; invalidate()
    }
    override fun onDraw(c:Canvas){
        super.onDraw(c)
        if(pose.isNotEmpty()){
            paint.style=Paint.Style.STROKE; paint.strokeWidth=5f; paint.color=Color.rgb(35,230,140)
            val edges=arrayOf(11 to 12,11 to 13,13 to 15,12 to 14,14 to 16,11 to 23,12 to 24,23 to 24,23 to 25,25 to 27,24 to 26,26 to 28,27 to 29,29 to 31,28 to 30,30 to 32)
            for((a,b) in edges) if(a<pose.size&&b<pose.size) c.drawLine(pose[a].x()*width,pose[a].y()*height,pose[b].x()*width,pose[b].y()*height,paint)
            paint.style=Paint.Style.FILL; paint.color=Color.WHITE
            pose.forEach{ if(it.visibility().orElse(0f)>.4f)c.drawCircle(it.x()*width,it.y()*height,5f,paint)}
        }
        if(path.size>1){
            paint.style=Paint.Style.STROKE; paint.strokeWidth=7f; paint.strokeCap=Paint.Cap.ROUND; paint.color=Color.rgb(255,170,55)
            val p=Path(); path.forEachIndexed{i,q->if(i==0)p.moveTo(q.x()*width,q.y()*height)else p.lineTo(q.x()*width,q.y()*height)}; c.drawPath(p,paint)
        }
        ball?.let{b->paint.style=Paint.Style.STROKE;paint.strokeWidth=4f;paint.color=Color.WHITE;c.drawOval(RectF(b.left*width,b.top*height,b.right*width,b.bottom*height),paint)}
        hoop?.let{h->paint.style=Paint.Style.STROKE;paint.strokeWidth=5f;paint.color=Color.RED;c.drawRect(RectF(h.left*width,h.top*height,h.right*width,h.bottom*height),paint)}
        event?.let{e->
            paint.style=Paint.Style.FILL;paint.textAlign=Paint.Align.CENTER;paint.typeface=Typeface.DEFAULT_BOLD;paint.textSize=64f
            paint.color=if(e=="MAKE")Color.rgb(50,235,135) else Color.rgb(255,75,100)
            c.drawText(if(e=="MAKE")"✓" else "✕",width/2f,height*.24f,paint)
        }
    }
}
