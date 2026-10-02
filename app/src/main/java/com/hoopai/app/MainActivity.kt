package com.hoopai.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.media.AudioManager
import android.media.ToneGenerator
import android.view.MotionEvent
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

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var metrics: TextView
    private lateinit var overlay: PoseOverlayView
    private lateinit var feedbackOverlay: FeedbackOverlayView
    private lateinit var categoryMenu: View
    private lateinit var categoryTitle: TextView
    private lateinit var categorySubtitle: TextView

    private var exercise = "شوتینگ"
    private var lastQuality = -1
    private var shootingMade = 0
    private var shootingAttempts = 0
    private var dribbleSuccess = 0
    private var tone: ToneGenerator? = null
    private var hoopX = Float.NaN
    private var hoopY = Float.NaN
    private var hoopRadius = 0.09f
    private var calibratingHoop = false
    private var shotActive = false
    private var shotStartY = Float.NaN
    private var shotPeakY = Float.NaN
    private var shotStartedAt = 0L
    private var ballWidthPx = 1f
    private var frameWidth = 1
    private var frameHeight = 1

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
        feedbackOverlay = findViewById(R.id.feedbackOverlay)
        categoryMenu = findViewById(R.id.categoryMenu)
        categoryTitle = findViewById(R.id.categoryTitle)
        categorySubtitle = findViewById(R.id.categorySubtitle)
        tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)

        val mainMenu = findViewById<View>(R.id.mainMenu)
        val cameraUi = findViewById<View>(R.id.cameraUi)
        val startExercise = findViewById<TextView>(R.id.startExercise)

        val menuShooting = findViewById<TextView>(R.id.menuShooting)
        val menuDribbling = findViewById<TextView>(R.id.menuDribbling)
        val menuDefense = findViewById<TextView>(R.id.menuDefense)
        val menuFootwork = findViewById<TextView>(R.id.menuFootwork)
        val menuFinishing = findViewById<TextView>(R.id.menuFinishing)
        val categoryBack = findViewById<Button>(R.id.categoryBack)
        val start1 = findViewById<TextView>(R.id.exerciseStart1)
        val start2 = findViewById<TextView>(R.id.exerciseStart2)
        val start3 = findViewById<TextView>(R.id.exerciseStart3)

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

        fun openCameraExercise(name: String) {
            exercise = name
            resetBallTracking()
            mainMenu.visibility = View.GONE
            cameraUi.visibility = View.VISIBLE
            preview.visibility = View.VISIBLE
            overlay.visibility = View.VISIBLE
            status.text = name + "  •  AI فعال • آماده تحلیل"
            if (name == "شوتینگ") shooting.isSelected = true
            if (name == "دریبلینگ") dribbling.isSelected = true
            if (name == "دفاع") defense.isSelected = true
            if (name == "فینیشینگ") finishing.isSelected = true
            updateMetrics(latestConfidence, latestVisible, lastQuality)
        }

        fun openCategory(name: String) {
            exercise = name
            categoryTitle.text = name
            categorySubtitle.text = "تمرین‌های تخصصی $name • انتخاب کن و بعد دوربین را شروع کن"
            val n1 = findViewById<TextView>(R.id.exerciseName1)
            val n2 = findViewById<TextView>(R.id.exerciseName2)
            val n3 = findViewById<TextView>(R.id.exerciseName3)
            val d1 = findViewById<TextView>(R.id.exerciseDesc1)
            val d2 = findViewById<TextView>(R.id.exerciseDesc2)
            val d3 = findViewById<TextView>(R.id.exerciseDesc3)
            when (name) {
                "شوتینگ" -> {
                    n1.text = "فرم شوت"; d1.text = "پا، تعادل، آرنج و رهاسازی"
                    n2.text = "شوت با شمارش"; d2.text = "گل / خطا + مسیر واقعی توپ"
                    n3.text = "چالش دقت"; d3.text = "تعداد گل، درصد موفقیت و ریتم"
                }
                "دریبلینگ" -> {
                    n1.text = "دریبل پایه"; d1.text = "دست، ارتفاع و ریتم توپ"
                    n2.text = "دریبل متناوب"; d2.text = "تعویض دست و کنترل واقعی توپ"
                    n3.text = "چالش سرعت"; d3.text = "تعداد دریبل موفق و ریتم حرکت"
                }
                "دفاع" -> {
                    n1.text = "استنس دفاعی"; d1.text = "زاویه زانو، لگن و مرکز ثقل"
                    n2.text = "اسلاید دفاعی"; d2.text = "حرکت جانبی و کنترل بدن"
                    n3.text = "کلوزاوت"; d3.text = "سرعت نزدیک شدن و تعادل"
                }
                "فوت‌ورک" -> {
                    n1.text = "جابجایی پایه"; d1.text = "ترتیب قدم‌ها و تعادل"
                    n2.text = "پیووت"; d2.text = "پای محوری و کنترل بدن"
                    n3.text = "تغییر جهت"; d3.text = "سرعت و کیفیت تغییر مسیر"
                }
                else -> {
                    n1.text = "لی‌آپ"; d1.text = "Gather، قدم‌ها و Takeoff"
                    n2.text = "فینیشینگ"; d2.text = "پای چپ / راست و رهاسازی"
                    n3.text = "چالش فینیش"; d3.text = "فرم، سرعت و نتیجه"
                }
            }
            mainMenu.visibility = View.GONE
            categoryMenu.visibility = View.VISIBLE
        }

        menuShooting.setOnClickListener { openCategory("شوتینگ") }
        menuDribbling.setOnClickListener { openCategory("دریبلینگ") }
        menuDefense.setOnClickListener { openCategory("دفاع") }
        menuFootwork.setOnClickListener { openCategory("فوت‌ورک") }
        menuFinishing.setOnClickListener { openCategory("فینیشینگ") }
        startExercise.setOnClickListener { openCategory(exercise) }

        start1.setOnClickListener { openCameraExercise(exercise) }
        start2.setOnClickListener { openCameraExercise(exercise) }
        start3.setOnClickListener { openCameraExercise(exercise) }
        categoryBack.setOnClickListener {
            categoryMenu.visibility = View.GONE
            mainMenu.visibility = View.VISIBLE
        }

        findViewById<Button>(R.id.backMenu).setOnClickListener {
            cameraUi.visibility = View.GONE
            preview.visibility = View.GONE
            overlay.visibility = View.GONE
            mainMenu.visibility = View.VISIBLE
        }

        findViewById<Button>(R.id.calibrateHoop).setOnClickListener {
            if (exercise != "شوتینگ") {
                status.text = "تنظیم حلقه فقط برای شوتینگ است"
            } else {
                calibratingHoop = true
                feedbackOverlay.clearTrail()
                status.text = "🎯 روی مرکز حلقه ضربه بزن"
            }
        }

        feedbackOverlay.setOnTouchListener { _, event ->
            if (calibratingHoop && event.action == MotionEvent.ACTION_UP) {
                hoopX = event.x / feedbackOverlay.width.toFloat()
                hoopY = event.y / feedbackOverlay.height.toFloat()
                hoopRadius = 0.085f
                calibratingHoop = false
                status.text = "🎯 حلقه ثبت شد • حالا شوت بزن"
                true
            } else false
        }

        findViewById<Button>(R.id.switchCamera).setOnClickListener {
            lens = if (lens == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            startCamera()
        }

        selectExercise("شوتینگ", shooting)

        mainMenu.visibility = View.VISIBLE
        cameraUi.visibility = View.GONE
        preview.visibility = View.GONE
        overlay.visibility = View.GONE

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
        shotActive = false
        shotStartY = Float.NaN
        shotPeakY = Float.NaN
        shotStartedAt = 0L
        feedbackOverlay.clearTrail()
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
        return if (den == 0.0) 0.0 else Math.toDegrees(acos((dot / den).coerceIn(-1.0, 1.0)))
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
                    if (exercise == "دریبلینگ" || exercise == "شوتینگ") {
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
            val label = category?.categoryName()?.lowercase() ?: ""
            label == "sports ball" || label == "sportsball" || label == "ball"
        } ?: return

        val box: RectF = detection.boundingBox()
        val centerY = (((box.top + box.bottom) / 2f) / frameHeight.toFloat()).coerceIn(0f, 1f)
        val centerX = (((box.left + box.right) / 2f) / frameWidth.toFloat()).coerceIn(0f, 1f)
        ballWidthPx = ((box.width() + box.height()) / 2f).coerceAtLeast(1f)
        feedbackOverlay.setBallPoint(centerX * feedbackOverlay.width, centerY * feedbackOverlay.height)

        if (exercise == "شوتینگ") {
            processShotBall(centerX, centerY, now)
            previousBallY = centerY
            return
        }
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

    private fun processShotBall(x: Float, y: Float, now: Long) {
        if (calibratingHoop || hoopX.isNaN()) return

        val previous = previousBallY
        val dy = if (previous.isNaN()) 0f else y - previous
        val nearHand = latestWristPositions.any { wrist ->
            val dx = wrist.first - x
            val dyHand = wrist.second - y
            sqrt(dx * dx + dyHand * dyHand) < 0.18f
        }

        if (!shotActive && nearHand && dy < -0.008f) {
            shotActive = true
            shotStartedAt = now
            shotStartY = y
            shotPeakY = y
            feedbackOverlay.clearTrail()
        }

        if (!shotActive) return
        shotPeakY = if (shotPeakY.isNaN()) y else minOf(shotPeakY, y)

        val distanceToHoop = sqrt(
            (x - hoopX) * (x - hoopX) +
            (y - hoopY) * (y - hoopY)
        )

        if (dy > 0.006f && distanceToHoop <= hoopRadius) {
            shootingMade++
            shootingAttempts++
            shotActive = false
            feedbackOverlay.showSuccess()
            tone?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
            updateMetrics(latestConfidence, latestVisible, lastQuality)
            return
        }

        val passedRim = shotPeakY < shotStartY - 0.06f && y > hoopY + hoopRadius * 1.6f
        val timedOut = now - shotStartedAt > 2600
        if (passedRim || timedOut) {
            shootingAttempts++
            shotActive = false
            feedbackOverlay.showMiss()
            tone?.startTone(ToneGenerator.TONE_PROP_NACK, 150)
            updateMetrics(latestConfidence, latestVisible, lastQuality)
        }
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
                        val bitmap = proxy.toBitmap()
                        frameWidth = bitmap.width
                        frameHeight = bitmap.height
                        val mp = BitmapImageBuilder(bitmap).build()
                        val timestamp = SystemClock.uptimeMillis()
                        landmarker?.detectAsync(mp, timestamp)
                        if (exercise == "دریبلینگ" || exercise == "شوتینگ") {
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
        tone?.release()
        analysisExecutor.shutdownNow()
        super.onDestroy()
    }
}
