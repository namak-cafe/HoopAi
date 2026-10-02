package com.hoopai.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.Executors
import android.widget.Button
import android.widget.TextView
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

class MainActivity : Activity() {
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var metrics: TextView
    private lateinit var overlay: PoseOverlayView
    private var exercise = "شوتینگ"
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
                        metrics.text = "اعتماد: $confidence%   نقاط بدن: $visible/$count"
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
