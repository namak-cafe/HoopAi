package com.hoopai.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt
import kotlin.math.toDegrees

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var metrics: TextView
    private lateinit var overlay: PoseOverlayView

    private var exercise = "شوتینگ"
    private var lastQuality = -1
    private var shootingMade = 0
    private var shootingAttempts = 0
    private var dribbleSuccess = 0

    private var lens = CameraSelector.LENS_FACING_BACK
    private var landmarker: PoseLandmarker? = null
    private var objectDetector: ObjectDetector? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var previousBallY = Float.NaN
    private var lowestBallY = Float.NaN
    private var ballWasDescending = false
    private var dribbleCandidate = false
    private var lastDribbleTime = 0L

    private var latestWristPositions: List<Pair<Float, Float>> = emptyList()
    private var latestConfidence = 0
    private var latestVisible = 0

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                setupAI()
                startCamera()
            } else {
                status.text = "دسترسی دوربین لازم است"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        metrics = findViewById(R.id.metrics)
        overlay = findViewById(R.id.poseOverlay)

        val shooting = findViewById<Button>(R.id.shooting)
        val dribbling = findViewById<Button>(R.id.dribbling)
        val defense = findViewById<Button>(R.id.defense)
        val finishing = findViewById<Button>(R.id.finishing)

        overlay.isClickable = false
        overlay.isFocusable = false
        findViewById<View>(R.id.exerciseBar).bringToFront()
        findViewById<View>(R.id.topPanel).bringToFront()

        shooting.setOnClickListener { selectExercise("شوتینگ", shooting) }
        dribbling.setOnClickListener { selectExercise("دریبلینگ", dribbling) }
        defense.setOnClickListener { selectExercise("دفاع", defense) }
        finishing.setOnClickListener { selectExercise("فینیشینگ", finishing) }

        findViewById<Button>(R.id.switchCamera).setOnClickListener {
            lens = if (lens == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            startCamera()
        }

        selectExercise("شوتینگ", shooting)

        if (!getPreferences(0).getBoolean("profile_done", false)) {
            showProfileWizard()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            setupAI()
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun selectExercise(name: String, selected: Button) {
        exercise = name
        resetBallTracking()

        val ids = intArrayOf(R.id.shooting, R.id.dribbling, R.id.defense, R.id.finishing)
        ids.forEach { findViewById<Button>(it).isSelected = false }
        selected.isSelected = true

        status.text = name + "  •  AI فعال • آماده تحلیل"
        updateMetrics(latestConfidence, latestVisible, lastQuality)
    }

    private fun resetBallTracking() {
        previousBallY = Float.NaN
        lowestBallY = Float.NaN
        ballWasDescending = false
        dribbleCandidate = false
        lastDribbleTime = 0L
    }

    private fun showProfileWizard() {
        val positions = arrayOf("گارد", "شوتینگ گارد", "فوروارد", "سنتر", "ترکیبی")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("پروفایل HoopAI")
            .setItems(positions) { _, which ->
                getPreferences(0).edit().putString("position", positions[which]).apply()
                askBodyInfo()
            }
            .setCancelable(false)
            .show()
    }

    private fun askBodyInfo() {
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 10, 40, 0)
        }
        val h = android.widget.EditText(this).apply {
            hint = "قد (سانتی‌متر)"
            inputType = 2
        }
        val w = android.widget.EditText(this).apply {
            hint = "وزن (کیلوگرم)"
            inputType = 2
        }
        box.addView(h)
        box.addView(w)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("اطلاعات بدنی")
            .setView(box)
            .setPositiveButton("ادامه") { _, _ ->
                getPreferences(0).edit()
                    .putString("height", h.text.toString())
                    .putString("weight", w.text.toString())
                    .apply()
                askGoal()
            }
            .setCancelable(false)
            .show()
    }

    private fun askGoal() {
        val goals = arrayOf("شوتینگ", "دریبل", "دفاع", "فینیشینگ", "آمادگی کلی")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("هدف اصلی")
            .setItems(goals) { _, which ->
                getPreferences(0).edit()
                    .putString("goal", goals[which])
                    .putBoolean("profile_done", true)
                    .apply()
                status.text = "شوتینگ  •  AI فعال • آماده تحلیل"
            }
            .setCancelable(false)
            .show()
    }

    private fun angle(
        a: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        b: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        c: com.google.mediapipe.tasks.components.containers.NormalizedLandmark
    ): Double {
        val ux = (a.x() - b.x()).toDouble()
        val uy = (a.y() - b.y()).toDouble()
        val vx = (c.x() - b.x()).toDouble()
        val vy = (c.y() - b.y()).toDouble()
        val dot = ux * vx + uy * vy
        val den = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
        return if (den == 0.0) 0.0 else toDegrees(acos((dot / den).coerceIn(-1.0, 1.0)))
    }

    private fun quality(
        pts: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>
    ): Int {
        if (pts.size < 29) return -1
        val leftKnee = angle(pts[23], pts[25], pts[27])
        val rightKnee = angle(pts[24], pts[26], pts[28])
        val leftElbow = angle(pts[11], pts[13], pts[15])
        val rightElbow = angle(pts[12], pts[14], pts[16])
        val knee = 100 - minOf(100.0, abs(((leftKnee + rightKnee) / 2) - 150.0) * 1.2)
        val arm = 100 - minOf(100.0, abs(((leftElbow + rightElbow) / 2) - 125.0) * 1.1)
        return (knee * 0.55 + arm * 0.45).toInt().coerceIn(0, 100)
    }

    private fun setupAI() {
        try {
            val poseBase = BaseOptions.builder()
                .setModelAssetPath("pose_landmarker_lite.task")
                .build()

            val poseOptions = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(poseBase)
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
                        val confidence = if (count == 0) 0 else ((visible.toFloat() / count) * 100).toInt()
                        latestConfidence = confidence
                        latestVisible = visible

                        if (pts.size >= 17) {
                            latestWristPositions = listOf(
                                Pair(pts[15].x(), pts[15].y()),
                                Pair(pts[16].x(), pts[16].y())
                            )
                        } else {
                            latestWristPositions = emptyList()
                        }

                        status.text = if (count > 0) {
                            exercise + "  •  AI فعال • بدن شناسایی شد"
                        } else {
                            exercise + "  •  بدن در کادر نیست"
                        }

                        val q = if (confidence >= 60) quality(pts) else -1
                        if (q >= 0) lastQuality = q
                        updateMetrics(confidence, visible, q)
                    }
                }
                .setErrorListener { error ->
                    runOnUiThread {
                        status.text = "خطای AI: " + (error.message ?: "unknown")
                    }
                }
                .build()

            landmarker = PoseLandmarker.createFromOptions(this, poseOptions)

            val ballBase = BaseOptions.builder()
                .setModelAssetPath("efficientdet_lite0.tflite")
                .build()

            val ballOptions = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(ballBase)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setScoreThreshold(0.30f)
                .setMaxResults(5)
                .setResultListener { result: ObjectDetectorResult, _ ->
                    if (exercise == "دریبلینگ") {
                        processBall(result)
                    }
                }
                .setErrorListener { }
                .build()

            objectDetector = ObjectDetector.createFromOptions(this, ballOptions)
        } catch (e: Exception) {
            status.text = "مدل AI بارگذاری نشد: " + (e.message ?: "unknown")
        }
    }

    private fun processBall(result: ObjectDetectorResult) {
        val detection = result.detections().firstOrNull { d ->
            val category = d.categories().firstOrNull()
            val label = category?.categoryName()?.orElse("")?.lowercase() ?: ""
            label == "sports ball" || label == "sportsball" || label == "ball"
        } ?: return

        val box: RectF = detection.boundingBox()
        val centerY = ((box.top + box.bottom) / 2f).coerceIn(0f, 1f)
        val centerX = ((box.left + box.right) / 2f).coerceIn(0f, 1f)
        val now = SystemClock.elapsedRealtime()

        val nearHand = latestWristPositions.any { wrist ->
            val dx = wrist.first - centerX
            val dy = wrist.second - centerY
            sqrt(dx * dx + dy * dy) < 0.20f
        }

        val previous = previousBallY
        if (!previous.isNaN()) {
            val dy = centerY - previous

            if (dy > 0.012f) {
                ballWasDescending = true
                lowestBallY = if (lowestBallY.isNaN()) centerY else maxOf(lowestBallY, centerY)
                if (nearHand && centerY > 0.35f) {
                    dribbleCandidate = true
                }
            }

            if (ballWasDescending && dy < -0.012f && dribbleCandidate) {
                if (now - lastDribbleTime > 220 && !lowestBallY.isNaN()) {
                    dribbleSuccess++
                    lastDribbleTime = now
                    runOnUiThread {
                        updateMetrics(latestConfidence, latestVisible, lastQuality)
                    }
                }
                ballWasDescending = false
                dribbleCandidate = false
                lowestBallY = Float.NaN
            }
        }

        previousBallY = centerY
    }

    private fun updateMetrics(confidence: Int, visible: Int, q: Int) {
        val text = when (exercise) {
            "دریبلینگ" ->
                "اعتماد: " + confidence + "%   •   دریبل موفق: " + dribbleSuccess + "   •   نقاط: " + visible
            "شوتینگ" ->
                "فرم: " + if (q >= 0) q.toString() + "/100" else "--" +
                    "   •   شوت موفق: " + shootingMade +
                    "   •   تلاش: " + shootingAttempts
            else ->
                "اعتماد: " + confidence + "%   •   فرم بدن: " +
                    if (q >= 0) q.toString() + "/100" else "--" +
                    "   •   نقاط: " + visible
        }
        metrics.text = text
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val selector = CameraSelector.Builder().requireLensFacing(lens).build()

            val previewUseCase = Preview.Builder().build().also {
                it.setSurfaceProvider(preview.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(analysisExecutor) { proxy ->
                try {
                    if (proxy.image != null) {
                        val mp = BitmapImageBuilder(proxy.toBitmap()).build()
                        val timestamp = SystemClock.uptimeMillis()
                        landmarker?.detectAsync(mp, timestamp)
                        if (exercise == "دریبلینگ") {
                            objectDetector?.detectAsync(mp, timestamp)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    proxy.close()
                }
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, previewUseCase, analysis)
                findViewById<View>(R.id.exerciseBar).bringToFront()
                findViewById<View>(R.id.topPanel).bringToFront()
            } catch (e: Exception) {
                status.text = "دوربین باز نشد: " + (e.message ?: "unknown")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        landmarker?.close()
        objectDetector?.close()
        analysisExecutor.shutdownNow()
        super.onDestroy()
    }
}
