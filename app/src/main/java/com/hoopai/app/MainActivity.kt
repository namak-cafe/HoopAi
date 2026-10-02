package com.hoopai.app

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
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
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.Executors
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private var preview: PreviewView? = null
    private var overlay: PoseOverlayView? = null
    private var status: TextView? = null
    private var metrics: TextView? = null
    private var landmarker: PoseLandmarker? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var lens = CameraSelector.LENS_FACING_BACK
    private var selectedCategory = "شوتینگ"
    private var selectedExercise = ""
    private var sessionStarted = 0L

    data class Cat(val icon: String, val title: String, val subtitle: String)
    data class Ex(val icon: String, val title: String, val subtitle: String)

    private val categories = listOf(
        Cat("🏀", "شوتینگ", "فرم، دقت و مسیر شوت"),
        Cat("🌀", "دریبلینگ", "کنترل توپ و تغییر دست"),
        Cat("🛡️", "دفاع", "استنس، اسلاید و کلوزاوت"),
        Cat("👣", "فوت‌ورک", "گام‌ها، پیوت و تعادل"),
        Cat("⚡", "چابکی", "سرعت و تغییر جهت"),
        Cat("🎯", "فینیشینگ", "گَدر، پرش و تمام‌کردن")
    )

    private val exercises = mapOf(
        "شوتینگ" to listOf(
            Ex("🎯", "Form Shooting", "تمرکز روی فرم بدن، تعادل، آرنج و رهاسازی"),
            Ex("🔥", "Spot Shooting", "شوت از نقاط مختلف با ثبت تلاش‌ها و نتیجه واقعی"),
            Ex("🏆", "Shot Challenge", "چالش شوت با آمار دقت و ریتم")
        ),
        "دریبلینگ" to listOf(
            Ex("↔️", "Crossover", "تغییر دست کنترل‌شده و حفظ ارتفاع توپ"),
            Ex("🔄", "Between the Legs", "کنترل توپ بین پاها و ریتم حرکت"),
            Ex("⚡", "Speed Dribble", "دریبل سرعتی با کنترل توپ")
        ),
        "دفاع" to listOf(
            Ex("🛡️", "Defensive Stance", "استنس، مرکز ثقل و وضعیت دست‌ها"),
            Ex("↔️", "Lateral Slides", "اسلاید جانبی و حفظ فاصله"),
            Ex("🚀", "Closeout", "نزدیک‌شدن کنترل‌شده به مهاجم")
        ),
        "فوت‌ورک" to listOf(
            Ex("👣", "Jump Stop", "توقف دوپا و کنترل تعادل"),
            Ex("🔄", "Pivot", "پیوت و حفظ پای محوری"),
            Ex("🎯", "Triple Threat", "قرارگیری و شروع حرکت از تریپل‌تِرِت")
        ),
        "چابکی" to listOf(
            Ex("⚡", "Change of Direction", "تغییر جهت سریع و کنترل‌شده"),
            Ex("🏃", "Quick Feet", "حرکت سریع پاها"),
            Ex("🎯", "Reaction", "واکنش و تغییر مسیر")
        ),
        "فینیشینگ" to listOf(
            Ex("🏀", "Layup", "گَدر، گام‌ها، پرش و رهاسازی"),
            Ex("💥", "Euro Step", "کنترل گام‌ها و تغییر مسیر"),
            Ex("🎯", "Contact Finish", "تعادل و کنترل در پایان حرکت")
        )
    )

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openCameraScreen()
        else Toast.makeText(this, "برای شروع تمرین به دسترسی دوربین نیاز است.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(10, 12, 24)
        window.navigationBarColor = Color.rgb(10, 12, 24)
        showHome()
    }

    private fun base(bg: Int = Color.rgb(10, 12, 24)): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            textDirection = View.TEXT_DIRECTION_RTL
        }

    private fun tv(text: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

    private fun gradient(vararg colors: Int, radius: Float = 28f) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = radius }

    private fun cardView(bg: GradientDrawable, padding: Int = 18) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(padding, padding, padding, padding)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            elevation = 8f
        }

    private fun addSpace(parent: ViewGroup, h: Int) {
        parent.addView(Space(this), LinearLayout.LayoutParams(1, h))
    }

    private fun smallButton(label: String) = Button(this).apply {
        text = label
        textSize = 13f
        setTextColor(Color.WHITE)
        isAllCaps = false
        background = gradient(Color.rgb(42, 47, 72), Color.rgb(27, 31, 52), 20f)
        setPadding(14, 0, 14, 0)
    }

    private fun primaryButton(label: String) = Button(this).apply {
        text = label
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
        isAllCaps = false
        background = gradient(Color.rgb(255, 145, 35), Color.rgb(236, 74, 72), 22f)
        elevation = 5f
    }

    private fun topBar(title: String, subtitle: String? = null, back: Boolean = false): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 18, 18, 10)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(tv(title, 27f, Color.WHITE, true))
        if (subtitle != null) box.addView(tv(subtitle, 13f, Color.rgb(174, 180, 202)))
        bar.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
        if (back) {
            val b = smallButton("←  بازگشت")
            b.setOnClickListener { showHome() }
            bar.addView(b, LinearLayout.LayoutParams(-2, 48))
        }
        return bar
    }

    private fun showHome() {
        root = FrameLayout(this)
        setContentView(root)
        val page = base()
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        val scroll = ScrollView(this).apply { isFillViewport = true }
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val content = base()
        content.setPadding(18, 10, 18, 28)
        scroll.addView(content)

        val hero = cardView(gradient(Color.rgb(41, 32, 74), Color.rgb(18, 54, 76)), 22)
        hero.addView(tv("🏀  HoopAI", 31f, Color.WHITE, true))
        hero.addView(tv("مربی هوشمند بسکتبال تو", 17f, Color.rgb(226, 229, 246), true))
        addSpace(hero, 8)
        hero.addView(tv("تمرین را انتخاب کن؛ دوربین فقط وقتی لازم باشد باز می‌شود.", 13f, Color.rgb(184, 193, 218)))
        content.addView(hero, LinearLayout.LayoutParams(-1, -2))
        addSpace(content, 18)

        val profile = cardView(gradient(Color.rgb(24, 29, 50), Color.rgb(19, 22, 39)), 16)
        val pos = getPreferences(Context.MODE_PRIVATE).getString("position", "هنوز تنظیم نشده")
        val goal = getPreferences(Context.MODE_PRIVATE).getString("goal", "هنوز تنظیم نشده")
        profile.addView(tv("👤 پروفایل بازیکن", 17f, Color.WHITE, true))
        profile.addView(tv("پست: " + pos + "   •   هدف: " + goal, 13f, Color.rgb(179, 187, 211)))
        addSpace(profile, 10)
        val edit = smallButton("ویرایش پروفایل")
        edit.setOnClickListener { showProfileWizard() }
        profile.addView(edit, LinearLayout.LayoutParams(-1, 46))
        content.addView(profile, LinearLayout.LayoutParams(-1, -2))
        addSpace(content, 18)

        content.addView(tv("تمرین‌ها", 21f, Color.WHITE, true))
        addSpace(content, 8)

        for ((index, cat) in categories.withIndex()) {
            val colors = when (index % 4) {
                0 -> intArrayOf(Color.rgb(38, 50, 92), Color.rgb(28, 95, 113))
                1 -> intArrayOf(Color.rgb(74, 43, 88), Color.rgb(38, 56, 104))
                2 -> intArrayOf(Color.rgb(28, 73, 69), Color.rgb(38, 43, 79))
                else -> intArrayOf(Color.rgb(83, 53, 38), Color.rgb(57, 42, 83))
            }
            val c = cardView(gradient(*colors, radius = 25f), 17)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
            row.addView(tv(cat.icon, 31f, Color.WHITE), LinearLayout.LayoutParams(52, 60))
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(tv(cat.title, 19f, Color.WHITE, true))
            t.addView(tv(cat.subtitle, 12f, Color.rgb(211, 217, 235)))
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(tv("‹", 32f, Color.WHITE, true), LinearLayout.LayoutParams(38, 60))
            c.addView(row)
            c.setOnClickListener { showCategory(cat.title) }
            content.addView(c, LinearLayout.LayoutParams(-1, 82))
            addSpace(content, 10)
        }

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val progress = smallButton("📊  پیشرفت من")
        progress.setOnClickListener { showProgress() }
        val today = smallButton("📅  برنامه امروز")
        today.setOnClickListener { showToday() }
        tools.addView(progress, LinearLayout.LayoutParams(0, 52, 1f))
        tools.addView(Space(this), LinearLayout.LayoutParams(10, 1))
        tools.addView(today, LinearLayout.LayoutParams(0, 52, 1f))
        content.addView(tools)
    }

    private fun showCategory(category: String) {
        selectedCategory = category
        root = FrameLayout(this)
        setContentView(root)
        val page = base()
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        page.addView(topBar(category, "تمرین موردنظر را انتخاب کن", true))

        val scroll = ScrollView(this)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val content = base()
        content.setPadding(18, 8, 18, 30)
        scroll.addView(content)

        val list = exercises[category].orEmpty()
        for ((i, ex) in list.withIndex()) {
            val c = cardView(gradient(Color.rgb(26, 30, 52), Color.rgb(18, 21, 38)), 18)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
            row.addView(tv(ex.icon, 29f), LinearLayout.LayoutParams(48, 62))
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            details.addView(tv(ex.title, 19f, Color.WHITE, true))
            details.addView(tv(ex.subtitle, 12f, Color.rgb(181, 188, 211)))
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            c.addView(row)
            addSpace(c, 12)
            val start = primaryButton("▶  شروع تمرین")
            start.setOnClickListener {
                selectedExercise = ex.title
                requestCamera()
            }
            c.addView(start, LinearLayout.LayoutParams(-1, 48))
            content.addView(c, LinearLayout.LayoutParams(-1, -2))
            if (i < list.lastIndex) addSpace(content, 12)
        }
    }

    private fun showProgress() {
        root = FrameLayout(this)
        setContentView(root)
        val page = base()
        root.addView(page)
        page.addView(topBar("📊 پیشرفت من", "آمار تمرین‌های انجام‌شده", true))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 12, 18, 18)
        }
        page.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val p = getPreferences(Context.MODE_PRIVATE)
        val sessions = p.getInt("sessions", 0)
        val minutes = p.getInt("minutes", 0)
        val card = cardView(gradient(Color.rgb(35, 47, 77), Color.rgb(23, 34, 57)), 22)
        card.addView(tv("🔥 وضعیت فعلی", 22f, Color.WHITE, true))
        addSpace(card, 14)
        card.addView(tv("تمرین‌ها: " + sessions, 18f, Color.WHITE))
        card.addView(tv("زمان تمرین: " + minutes + " دقیقه", 18f, Color.WHITE))
        card.addView(tv("هر آماری فقط از تمرین واقعی ثبت می‌شود؛ شمارش ساختگی نداریم.", 13f, Color.rgb(188, 198, 220)))
        content.addView(card)
    }

    private fun showToday() {
        root = FrameLayout(this)
        setContentView(root)
        val page = base()
        root.addView(page)
        page.addView(topBar("📅 برنامه امروز", "تمرین پیشنهادی بر اساس پروفایل", true))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 12, 18, 18)
        }
        page.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val goal = getPreferences(Context.MODE_PRIVATE).getString("goal", "آمادگی کلی") ?: "آمادگی کلی"
        val plan = when (goal) {
            "شوتینگ" -> listOf("Form Shooting", "Spot Shooting", "Quick Feet")
            "دریبل" -> listOf("Crossover", "Between the Legs", "Speed Dribble")
            "دفاع" -> listOf("Defensive Stance", "Lateral Slides", "Closeout")
            "فینیشینگ" -> listOf("Layup", "Euro Step", "Quick Feet")
            else -> listOf("Form Shooting", "Crossover", "Lateral Slides")
        }
        val intro = cardView(gradient(Color.rgb(48, 36, 75), Color.rgb(28, 58, 76)), 20)
        intro.addView(tv("🎯 تمرکز امروز", 21f, Color.WHITE, true))
        intro.addView(tv("هدف پروفایل: " + goal, 14f, Color.rgb(210, 216, 236)))
        content.addView(intro)
        addSpace(content, 14)
        for ((i, item) in plan.withIndex()) {
            val c = cardView(gradient(Color.rgb(25, 29, 49), Color.rgb(19, 22, 38)), 16)
            c.addView(tv((i + 1).toString() + ".  " + item, 18f, Color.WHITE, true))
            c.addView(tv("تمرین واقعی با ثبت پیشرفت", 12f, Color.rgb(174, 182, 205)))
            content.addView(c, LinearLayout.LayoutParams(-1, 78))
            addSpace(content, 9)
        }
    }

    private fun showProfileWizard() {
        val positions = arrayOf("گارد", "شوتینگ گارد", "فوروارد", "سنتر", "ترکیبی")
        AlertDialog.Builder(this)
            .setTitle("پروفایل HoopAI")
            .setMessage("پست بازی را انتخاب کن")
            .setItems(positions) { _, which ->
                getPreferences(0).edit().putString("position", positions[which]).apply()
                askBodyInfo()
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun askBodyInfo() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(45, 8, 45, 0)
        }
        val h = EditText(this).apply { hint = "قد (سانتی‌متر)"; inputType = 2 }
        val w = EditText(this).apply { hint = "وزن (کیلوگرم)"; inputType = 2 }
        box.addView(h)
        box.addView(w)
        AlertDialog.Builder(this)
            .setTitle("اطلاعات بدنی")
            .setView(box)
            .setPositiveButton("ادامه") { _, _ ->
                getPreferences(0).edit().putString("height", h.text.toString()).putString("weight", w.text.toString()).apply()
                askGoal()
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun askGoal() {
        val goals = arrayOf("شوتینگ", "دریبل", "دفاع", "فینیشینگ", "آمادگی کلی")
        AlertDialog.Builder(this)
            .setTitle("هدف اصلی")
            .setItems(goals) { _, which ->
                getPreferences(0).edit().putString("goal", goals[which]).apply()
                showHome()
            }
            .setCancelable(false)
            .show()
    }

    private fun requestCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCameraScreen()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCameraScreen() {
        root = FrameLayout(this)
        setContentView(root)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; setBackgroundColor(Color.BLACK) }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        overlay = PoseOverlayView(this)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        val hud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
            background = gradient(Color.argb(220, 9, 12, 25), Color.argb(120, 9, 12, 25), 0f)
        }
        status = tv("در حال آماده‌سازی AI…", 14f, Color.WHITE)
        metrics = tv("اعتماد: --   •   نقاط بدن: --", 13f, Color.rgb(210, 216, 232))
        hud.addView(tv("🏀 " + selectedCategory + "  •  " + selectedExercise, 18f, Color.WHITE, true))
        hud.addView(status)
        hud.addView(metrics)
        val hp = FrameLayout.LayoutParams(-1, -2)
        hp.gravity = Gravity.TOP
        root.addView(hud, hp)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(12, 10, 12, 18)
            background = gradient(Color.argb(210, 8, 10, 20), Color.argb(100, 8, 10, 20), 0f)
        }
        val back = smallButton("✕  خروج")
        back.setOnClickListener { stopCameraAndBack() }
        val switch = smallButton("↻  دوربین")
        switch.setOnClickListener {
            lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }
        controls.addView(back, LinearLayout.LayoutParams(0, 50, 1f))
        controls.addView(Space(this), LinearLayout.LayoutParams(10, 1))
        controls.addView(switch, LinearLayout.LayoutParams(0, 50, 1f))
        val cp = FrameLayout.LayoutParams(-1, -2)
        cp.gravity = Gravity.BOTTOM
        root.addView(controls, cp)

        sessionStarted = SystemClock.elapsedRealtime()
        setupAI()
        startCamera()
    }

    private fun stopCameraAndBack() {
        saveSession()
        landmarker?.close()
        landmarker = null
        showCategory(selectedCategory)
    }

    private fun angle(a: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
                      b: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
                      c: com.google.mediapipe.tasks.components.containers.NormalizedLandmark): Double {
        val ux = (a.x() - b.x()).toDouble()
        val uy = (a.y() - b.y()).toDouble()
        val vx = (c.x() - b.x()).toDouble()
        val vy = (c.y() - b.y()).toDouble()
        val dot = ux * vx + uy * vy
        val den = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
        return if (den == 0.0) 0.0 else Math.toDegrees(acos((dot / den).coerceIn(-1.0, 1.0)))
    }

    private fun quality(pts: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Int {
        if (pts.size < 29) return -1
        val leftKnee = angle(pts[23], pts[25], pts[27])
        val rightKnee = angle(pts[24], pts[26], pts[28])
        val leftElbow = angle(pts[11], pts[13], pts[15])
        val rightElbow = angle(pts[12], pts[14], pts[16])
        val knee = 100 - min(100.0, kotlin.math.abs(((leftKnee + rightKnee) / 2) - 150.0) * 1.2)
        val arm = 100 - min(100.0, kotlin.math.abs(((leftElbow + rightElbow) / 2) - 125.0) * 1.1)
        return (knee * .55 + arm * .45).toInt().coerceIn(0, 100)
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
                        overlay?.setLandmarks(pts)
                        val count = pts.size
                        val visible = pts.count { it.visibility().orElse(0f) > 0.5f }
                        val confidence = if (count == 0) 0 else visible * 100 / count
                        status?.text = if (count > 0) "AI فعال • اسکلت بدن شناسایی شد" else "بدن در کادر نیست"
                        val q = if (confidence >= 60) quality(pts) else -1
                        metrics?.text = if (q >= 0)
                            "اعتماد: " + confidence + "%   •   کیفیت فرم: " + q + "/100   •   نقاط: " + visible + "/" + count
                        else
                            "اعتماد: " + confidence + "%   •   نقاط بدن: " + visible + "/" + count
                    }
                }
                .setErrorListener { error ->
                    runOnUiThread { status?.text = "خطای AI: " + (error.message ?: "unknown") }
                }
                .build()
            landmarker = PoseLandmarker.createFromOptions(this, options)
        } catch (e: Exception) {
            status?.text = "مدل AI بارگذاری نشد: " + (e.message ?: "unknown")
        }
    }

    private fun startCamera() {
        val p = preview ?: return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val selector = CameraSelector.Builder().requireLensFacing(lens).build()
                val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(p.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(analysisExecutor) { proxy ->
                    try {
                        if (proxy.image != null) {
                            val mp = BitmapImageBuilder(proxy.toBitmap()).build()
                            landmarker?.detectAsync(mp, SystemClock.uptimeMillis())
                        }
                    } catch (_: Exception) {
                    } finally {
                        proxy.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, previewUseCase, analysis)
            } catch (e: Exception) {
                status?.text = "دوربین باز نشد: " + (e.message ?: "unknown")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun saveSession() {
        val elapsed = ((SystemClock.elapsedRealtime() - sessionStarted) / 60000).toInt().coerceAtLeast(1)
        val p = getPreferences(Context.MODE_PRIVATE)
        p.edit().putInt("sessions", p.getInt("sessions", 0) + 1).putInt("minutes", p.getInt("minutes", 0) + elapsed).apply()
    }

    override fun onDestroy() {
        landmarker?.close()
        analysisExecutor.shutdownNow()
        super.onDestroy()
    }
}
