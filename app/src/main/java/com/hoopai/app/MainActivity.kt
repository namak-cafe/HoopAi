package com.hoopai.app

import android.Manifest
import androidx.activity.ComponentActivity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.Executors
import android.widget.Button
import android.widget.TextView
import android.widget.EditText
import android.app.AlertDialog
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var metrics: TextView
    private lateinit var overlay: PoseOverlayView
    private var exercise = "شوتینگ"
    private var reps = 0
    private var lastQuality = -1
    private var lens = CameraSelector.LENS_FACING_BACK
    private var landmarker: PoseLandmarker? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        metrics = findViewById(R.id.metrics)
        overlay = findViewById(R.id.poseOverlay)
        findViewById<Button>(R.id.shooting).setOnClickListener { exercise = "شوتینگ"; titleUpdate() }
        findViewById<Button>(R.id.dribbling).setOnClickListener { exercise = "دریبل"; titleUpdate() }
        findViewById<Button>(R.id.defense).setOnClickListener { exercise = "دفاع"; titleUpdate() }
        findViewById<Button>(R.id.finishing).setOnClickListener { exercise = "فینیشینگ"; titleUpdate() }

        findViewById<Button>(R.id.switchCamera).setOnClickListener {
            lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }

        if (!getPreferences(0).getBoolean("profile_done", false)) showProfileWizard()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setupAI()
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 10 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            setupAI()
            startCamera()
        } else status.text = "دسترسی دوربین لازم است"
    }

    private fun titleUpdate() { status.text = "$exercise • آماده تحلیل" }

    private fun showProfileWizard() {
        val positions = arrayOf("گارد", "شوتینگ گارد", "فوروارد", "سنتر", "ترکیبی")
        AlertDialog.Builder(this).setTitle("پروفایل HoopAI").setItems(positions) { _, which ->
            getPreferences(0).edit().putString("position", positions[which]).apply()
            askBodyInfo()
        }.setCancelable(false).show()
    }

    private fun askBodyInfo() {
        val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(40,10,40,0) }
        val h = EditText(this).apply { hint = "قد (سانتی‌متر)" ; inputType = 2 }
        val w = EditText(this).apply { hint = "وزن (کیلوگرم)" ; inputType = 2 }
        box.addView(h); box.addView(w)
        AlertDialog.Builder(this).setTitle("اطلاعات بدنی").setView(box).setPositiveButton("ادامه") { _, _ ->
            getPreferences(0).edit().putString("height", h.text.toString()).putString("weight", w.text.toString()).apply()
            askGoal()
        }.setCancelable(false).show()
    }

    private fun askGoal() {
        val goals = arrayOf("شوتینگ", "دریبل", "دفاع", "فینیشینگ", "آمادگی کلی")
        AlertDialog.Builder(this).setTitle("هدف اصلی").setItems(goals) { _, which ->
            getPreferences(0).edit().putString("goal", goals[which]).putBoolean("profile_done", true).apply()
            titleUpdate()
        }.setCancelable(false).show()
    }

    private fun angle(a: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
                      b: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
                      c: com.google.mediapipe.tasks.components.containers.NormalizedLandmark): Double {
        val ux=(a.x()-b.x()).toDouble(); val uy=(a.y()-b.y()).toDouble()
        val vx=(c.x()-b.x()).toDouble(); val vy=(c.y()-b.y()).toDouble()
        val dot=ux*vx+uy*vy
        val den=Math.sqrt(ux*ux+uy*uy)*Math.sqrt(vx*vx+vy*vy)
        return if (den==0.0) 0.0 else Math.toDegrees(Math.acos((dot/den).coerceIn(-1.0,1.0)))
    }

    private fun quality(pts: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Int {
        if (pts.size < 29) return -1
        val leftKnee=angle(pts[23],pts[25],pts[27])
        val rightKnee=angle(pts[24],pts[26],pts[28])
        val leftElbow=angle(pts[11],pts[13],pts[15])
        val rightElbow=angle(pts[12],pts[14],pts[16])
        val knee=100-Math.min(100.0,Math.abs(((leftKnee+rightKnee)/2)-150.0)*1.2)
        val arm=100-Math.min(100.0,Math.abs(((leftElbow+rightElbow)/2)-125.0)*1.1)
        return ((knee*0.55+arm*0.45).toInt()).coerceIn(0,100)
    }

    private fun setupAI() {
        try {
            val base = BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build()
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener { result: PoseLandmarkerResult, _ ->
                    runOnUiThread {
                        val pts = result.landmarks().firstOrNull() ?: emptyList()
                        val count = pts.size
                        overlay.setLandmarks(pts)
                        val visible = pts.count { it.visibility().orElse(0f) > 0.5f }
                        val confidence = if (count == 0) 0 else ((visible.toFloat()/count)*100).toInt()
                        status.text = if (count > 0) "$exercise • AI فعال • بدن شناسایی شد" else "$exercise • بدن در کادر نیست"
                        val q = if (confidence >= 60) quality(pts) else -1
                        if (q >= 0) lastQuality = q
                        metrics.text = if (q >= 0) "اعتماد: $confidence%   فرم بدن: $q/100   نقاط: $visible/$count" else "اعتماد: $confidence%   نقاط بدن: $visible/$count"
                    }
                }
                .setErrorListener { error -> runOnUiThread { status.text = "خطای AI: ${error.message ?: "unknown"}" } }
                .build()
            landmarker = PoseLandmarker.createFromOptions(this, options)
        } catch (e: Exception) {
            status.text = "مدل AI بارگذاری نشد: ${e.message ?: "unknown"}"
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val selector = CameraSelector.Builder().requireLensFacing(lens).build()
            val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(analysisExecutor) { proxy ->
                try {
                    val image = proxy.image
                    if (image != null) {
                        val mp = BitmapImageBuilder(proxy.toBitmap()).build()
                        landmarker?.detectAsync(mp, SystemClock.uptimeMillis())
                    }
                } catch (_: Exception) {
                } finally {
                    proxy.close()
                }
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, previewUseCase, analysis)
            } catch (e: Exception) {
                status.text = "دوربین باز نشد: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        landmarker?.close()
        analysisExecutor.shutdownNow()
        super.onDestroy()
    }
}
