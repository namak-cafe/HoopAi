package com.hoopai.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class AiWorkoutActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var overlay: WorkoutOverlayView
    private lateinit var analyzer: BasketballAnalyzer
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var coach: TextView
    private lateinit var title: TextView
    private var category = "finishing"
    private var drill = "Right Layup"
    private var startedAt = 0L
    private var manualAttempts = 0
    private var manualMakes = 0
    private var targetMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        category = intent.getStringExtra("category") ?: "finishing"
        drill = intent.getStringExtra("drill") ?: "Right Layup"
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        overlay = WorkoutOverlayView(this)
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        overlay.setOnTouchListener { _, e ->
            if (targetMode && e.action == android.view.MotionEvent.ACTION_UP) {
                analyzer.setHoopTarget(PointF(e.x, e.y), overlay.width, overlay.height); targetMode=false
                coach.text = "🎯 هدف ثبت شد؛ حالا تمرین را شروع کن."
                true
            } else false
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 8)
            background = rounded(Color.argb(205, 5, 15, 25), 18f)
        }
        title = TextView(this).apply {
            text = "🤖 HoopAI  •  " + pretty(category)
            setTextColor(Color.WHITE); textSize = 19f; setTypeface(null, Typeface.BOLD)
        }
        status = TextView(this).apply {
            text = "در حال آماده‌سازی هوش مصنوعی..."
            setTextColor(Color.rgb(150, 230, 190)); textSize = 13f
        }
        stats = TextView(this).apply {
            text = "تلاش ۰   •   موفق ۰   •   دقت —"
            setTextColor(Color.WHITE); textSize = 16f
        }
        top.addView(title); top.addView(status); top.addView(stats)
        root.addView(top, FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.TOP; leftMargin=12; rightMargin=12 })

        coach = TextView(this).apply {
            text = "دوربین را طوری بگذار که کل بدن و توپ دیده شوند."
            setTextColor(Color.WHITE); textSize = 14f; setPadding(16,12,16,12)
            background = rounded(Color.argb(210, 5, 15, 25), 18f)
        }
        root.addView(coach, FrameLayout.LayoutParams(-1, -2).apply {
            gravity=Gravity.BOTTOM; leftMargin=12; rightMargin=12; bottomMargin=92
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(8,8,8,10)
            background = rounded(Color.argb(220, 5, 15, 25), 22f)
        }
        val finish = button("پایان") { finishWorkout() }
        val target = button("🎯 هدف") { targetMode=true; coach.text="روی حلقه یا سطل ضربه بزن تا هدف شوت ثبت شود." }
        val make = button("✓ ثبت موفق") { manualRep(true) }
        val miss = button("✕ ثبت ناموفق") { manualRep(false) }
        controls.addView(target, LinearLayout.LayoutParams(0,58,1f))
        controls.addView(make, LinearLayout.LayoutParams(0,58,1f))
        controls.addView(miss, LinearLayout.LayoutParams(0,58,1f))
        controls.addView(finish, LinearLayout.LayoutParams(0,58,0.8f))
        root.addView(controls, FrameLayout.LayoutParams(-1, -2).apply { gravity=Gravity.BOTTOM; leftMargin=10; rightMargin=10 })

        val close = button("×") { finish() }.apply { textSize=24f; background=rounded(Color.argb(190,0,0,0),50f) }
        root.addView(close, FrameLayout.LayoutParams(58,58).apply { gravity=Gravity.TOP or Gravity.END; topMargin=12; rightMargin=12 })

        setContentView(root)
        startedAt = SystemClock.elapsedRealtime()
        analyzer = BasketballAnalyzer(this) { updateFromAi(it) }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val previewUse = Preview.Builder().setTargetRotation(preview.display.rotation).build()
            previewUse.setSurfaceProvider(preview.surfaceProvider)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(android.util.Size(960, 540))
                .build()
            analysis.setAnalyzer(cameraExecutor) { proxy -> analyzer.analyze(proxy) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUse, analysis)
                status.text = "هوش مصنوعی فعال • بدن + توپ در حال رهگیری"
            } catch (_: Exception) { status.text = "خطا در راه‌اندازی دوربین" }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateFromAi(s: BasketballAnalyzer.LiveStats) {
        runOnUiThread {
            overlay.setState(s.ballPath, s.ballBox, s.pose, s.hoopBox, s.event)
            stats.text = "تلاش " + s.attempts + "   •   موفق " + s.makes + "   •   دقت " +
                    (if (s.attempts > 0) s.makes*100/s.attempts else 0) + "%   •   فرم " + s.formScore
            status.text = if (s.confidence < .45f) "در انتظار تشخیص مطمئن..." else "رهگیری زنده • اعتماد " + (s.confidence*100).toInt() + "%"
            if (s.coach.isNotBlank()) coach.text = "🤖 مربی هوشمند: " + s.coach
            if (s.event == "MAKE") coach.text = "🟢 شوت موفق — توپ واقعاً وارد ناحیه هدف شد."
            if (s.event == "MISS") coach.text = "🔴 حرکت/شوت ناموفق — ادامه بده؛ تمرین متوقف نمی‌شود."
        }
    }

    private fun manualRep(make: Boolean) {
        manualAttempts++
        if (make) manualMakes++
        analyzer.manualRep(make)
    }

    private fun finishWorkout() {
        analyzer.stop()
        val duration = max(1, ((SystemClock.elapsedRealtime()-startedAt)/60000).toInt())
        val s = analyzer.stats()
        val attempts = max(s.attempts, manualAttempts)
        val makes = max(s.makes, manualMakes)
        val acc = if (attempts == 0) 0 else makes*100/attempts
        val report = Intent(this, AiReportActivity::class.java).apply {
            putExtra("category", category); putExtra("drill", drill)
            putExtra("attempts", attempts); putExtra("makes", makes)
            putExtra("accuracy", acc); putExtra("form", s.formScore)
            putExtra("minutes", duration); putExtra("feedback", s.coach)
        }
        startActivity(report)
        finish()
    }

    override fun onDestroy() {
        if (::analyzer.isInitialized) analyzer.stop()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun pretty(x:String) = when(x.lowercase()) {
        "shooting"->"شوتینگ"; "dribbling"->"دریبلینگ"; "defense"->"دفاع"
        "agility"->"چابکی"; "footwork"->"فوت‌ورک"; "finishing"->"فینیشینگ"; else->x
    }
    private fun button(t:String, click:()->Unit)=Button(this).apply {
        text=t; setTextColor(Color.WHITE); textSize=13f; setOnClickListener{click()}
        background=rounded(Color.argb(210,18,38,55),22f)
    }
    private fun rounded(color:Int, radius:Float)=android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius=radius
    }
}

class BasketballAnalyzer(
    private val context: android.content.Context,
    private val listener: (LiveStats)->Unit
) {
    data class LiveStats(
        val attempts:Int, val makes:Int, val formScore:Int, val confidence:Float,
        val coach:String, val event:String?, val ballPath:List<PointF>,
        val ballBox:RectF?, val hoopBox:RectF?, val pose:List<NormalizedLandmark>
    )
    private var poseLandmarker: PoseLandmarker? = null
    private var objectDetector: ObjectDetector? = null
    private var attempts=0; private var makes=0; private var formScore=0
    private var confidence=0f
    private var lastBall: PointF?=null
    private val path=ArrayDeque<PointF>()
    private var lastBallTime=0L
    private var shotState=0
    private var risePeakY=0f
    private var lastEvent: String?=null
    private var lastEventTime=0L
    private var latestPose: List<NormalizedLandmark> = emptyList()
    private var latestBall: RectF? = null
    private var latestHoop: RectF? = null
    private var hoopCenter: PointF? = null
    private var hoopNorm: PointF? = null
    private var frameW=1f
    private var frameH=1f
    private var kneeAngle=180f
    private var lastCoach="بدن را کامل داخل کادر نگه دار."

    init {
        try {
            val base=BaseOptions.builder().setModelAssetPath("pose_landmarker_full.task").build()
            poseLandmarker=PoseLandmarker.createFromOptions(context, PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(base).setRunningMode(RunningMode.LIVE_STREAM)
                .setMinPoseDetectionConfidence(.55f).setMinPosePresenceConfidence(.55f)
                .setMinTrackingConfidence(.55f)
                .setResultListener { r, _ -> onPose(r) }.setErrorListener { }.build())
        } catch (_:Exception) {}
        try {
            val base=BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build()
            objectDetector=ObjectDetector.createFromOptions(context, ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(base).setRunningMode(RunningMode.LIVE_STREAM)
                .setScoreThreshold(.28f).setMaxResults(8)
                .setResultListener { r, _ -> onObjects(r) }.setErrorListener { }.build())
        } catch (_:Exception) {}
    }

    fun analyze(proxy: ImageProxy) {
        try {
            val bmp=proxyToBitmap(proxy)
            frameW=bmp.width.toFloat(); frameH=bmp.height.toFloat()
            val image=BitmapImageBuilder(bmp).build()
            val ts=SystemClock.elapsedRealtime()
            poseLandmarker?.detectAsync(image, ts)
            objectDetector?.detectAsync(image, ts)
        } catch (_:Exception) {} finally { proxy.close() }
    }

    private fun onPose(r: PoseLandmarkerResult) {
        val p=r.landmarks().firstOrNull() ?: return
        latestPose=p
        if (p.size > 28) {
            val vis=(p[11].visibility().orElse(0f)+p[12].visibility().orElse(0f)+p[23].visibility().orElse(0f)+p[24].visibility().orElse(0f))/4f
            if(vis>.55f) {
                kneeAngle=(angle(p[23],p[25],p[27])+angle(p[24],p[26],p[28]))/2f
                formScore=calcForm()
                lastCoach=coachForPose()
            }
        }
        emit()
    }

    private fun onObjects(r:ObjectDetectorResult) {
        var ball:RectF?=null
        var hoop:RectF?=null
        for(d in r.detections()) {
            val c=d.categories().maxByOrNull{it.score()} ?: continue
            val label=(c.categoryName() ?: "").lowercase()
            val b=d.boundingBox()
            if(label.contains("ball") && c.score()>.35f) ball=RectF(b.left/frameW,b.top/frameH,b.right/frameW,b.bottom/frameH)
            if((label.contains("basket")||label.contains("hoop")) && c.score()>.35f) hoop=RectF(b.left/frameW,b.top/frameH,b.right/frameW,b.bottom/frameH)
        }
        latestBall=ball; latestHoop=hoop
        if(ball!=null) processBall(ball)
        emit()
    }

    private fun processBall(b:RectF) {
        val center=PointF(b.centerX()/frameW,b.centerY()/frameH)
        val now=SystemClock.elapsedRealtime()
        val prev=lastBall
        if(prev!=null && now-lastBallTime<900) {
            val dy=center.y-prev.y
            if(shotState==0 && dy < -7f) { shotState=1; risePeakY=center.y; path.clear() }
            if(shotState==1) {
                path.addLast(center); while(path.size>40) path.removeFirst()
                risePeakY=min(risePeakY,center.y)
                if(dy>8f && center.y>risePeakY+12f) shotState=2
            } else if(shotState==2 && dy>8f && center.y>risePeakY+35f) {
                attempts++
                val made=hoopNorm?.let{ h -> abs(center.x-h.x)<0.12f && abs(center.y-h.y)<0.14f } ?: false
                if(made) makes++
                lastEvent=if(made)"MAKE" else "MISS"; lastEventTime=now
                shotState=0; path.clear()
            }
        }
        lastBall=center; lastBallTime=now
    }

    private fun calcForm():Int {
        var score=70
        if(kneeAngle in 125f..175f) score+=8
        if(kneeAngle in 145f..170f) score+=5
        return min(98,score)
    }
    private fun coachForPose():String = when {
        kneeAngle>175f -> "زانوها کمی نرم‌تر؛ تعادل و کنترل بهتر می‌شود."
        kneeAngle<105f -> "خیلی پایین نشستی؛ حرکت را کنترل‌شده‌تر انجام بده."
        else -> "بدن در کادر است؛ روی تکرار تمیز و ریتم ثابت تمرکز کن."
    }
    private fun emit() {
        val now=SystemClock.elapsedRealtime()
        val event=if(now-lastEventTime<900) lastEvent else null
        confidence=max(if(latestPose.isNotEmpty()) .75f else 0f, if(latestBall!=null).72f else 0f)
        listener(LiveStats(attempts,makes,formScore,confidence,lastCoach,event,path.toList(),latestBall,latestHoop,latestPose))
    }
    fun setHoopTarget(p:PointF, viewW:Int, viewH:Int){ hoopCenter=p; hoopNorm=PointF(p.x/viewW.toFloat(),p.y/viewH.toFloat()); latestHoop=RectF(hoopNorm!!.x-.04f,hoopNorm!!.y-.03f,hoopNorm!!.x+.04f,hoopNorm!!.y+.03f); emit() }
    fun manualRep(make:Boolean){ attempts++; if(make)makes++; lastEvent=if(make)"MAKE" else "MISS"; lastEventTime=SystemClock.elapsedRealtime(); emit() }
    fun stats()=LiveStats(attempts,makes,formScore,confidence,lastCoach,null,path.toList(),latestBall,latestHoop,latestPose)
    fun stop(){poseLandmarker?.close();objectDetector?.close()}
    private fun angle(a:NormalizedLandmark,b:NormalizedLandmark,c:NormalizedLandmark):Float{
        val abx=a.x()-b.x(); val aby=a.y()-b.y(); val cbx=c.x()-b.x(); val cby=c.y()-b.y()
        val dot=abx*cbx+aby*cby; val den=max(.0001f,sqrt(abx*abx+aby*aby)*sqrt(cbx*cbx+cby*cby))
        return Math.toDegrees(kotlin.math.acos((dot/den).coerceIn(-1f,1f)).toDouble()).toFloat()
    }
    private fun proxyToBitmap(proxy:ImageProxy):Bitmap{
        val image=proxy.image ?: throw IllegalStateException()
        val y=image.planes[0].buffer; val u=image.planes[1].buffer; val v=image.planes[2].buffer
        val ySize=y.remaining(); val uSize=u.remaining(); val vSize=v.remaining()
        val nv21=ByteArray(ySize+uSize+vSize); y.get(nv21,0,ySize); v.get(nv21,ySize,vSize); u.get(nv21,ySize+vSize,uSize)
        val yuv=android.graphics.YuvImage(nv21,android.graphics.ImageFormat.NV21,image.width,image.height,null)
        val out=ByteArrayOutputStream(); yuv.compressToJpeg(Rect(0,0,image.width,image.height),82,out)
        var bmp=BitmapFactory.decodeByteArray(out.toByteArray(),0,out.size())
        val m=Matrix().apply{postRotate(proxy.imageInfo.rotationDegrees.toFloat())}
        bmp=Bitmap.createBitmap(bmp,0,0,bmp.width,bmp.height,m,true)
        return bmp
    }
}
